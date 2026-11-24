package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	nethttp "net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	_ "unsafe"

	"github.com/oschwald/maxminddb-golang"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/boxapi"
	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/dns/transport/hosts"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/dns/transport/quic"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/anytls"
	"github.com/sagernet/sing-box/protocol/block"
	"github.com/sagernet/sing-box/protocol/direct"
	protocolDns "github.com/sagernet/sing-box/protocol/dns"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/http"
	"github.com/sagernet/sing-box/protocol/hysteria"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/redirect"
	"github.com/sagernet/sing-box/protocol/shadowsocks"
	"github.com/sagernet/sing-box/protocol/shadowtls"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing-box/protocol/ssh"
	"github.com/sagernet/sing-box/protocol/tor"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/tuic"
	"github.com/sagernet/sing-box/protocol/tun"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	"github.com/sagernet/sing-box/protocol/wireguard"
	singService "github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"

	_ "github.com/sagernet/sing-box/experimental/clashapi"
	_ "github.com/sagernet/sing-box/transport/v2rayquic"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

var assetsDir string

func registerGeoHooks(dir string) {
	assetsDir = dir
	nekoutils.GetGeoIPHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		reader, err := maxminddb.Open(filepath.Join(assetsDir, "geoip.db"))
		if err != nil {
			return nil, err
		}
		defer reader.Close()

		networks := reader.Networks(maxminddb.SkipAliasedNetworks)
		countryMap := make(map[string][]*net.IPNet)
		var nextCode string
		for networks.Next() {
			ipNet, err := networks.Network(&nextCode)
			if err != nil {
				return nil, fmt.Errorf("failed to get network: %w", err)
			}
			countryMap[nextCode] = append(countryMap[nextCode], ipNet)
		}
		ipNets := countryMap[strings.ToLower(name)]
		if len(ipNets) == 0 {
			return nil, fmt.Errorf("no networks found for country code: %s", name)
		}
		var headlessRule option.DefaultHeadlessRule
		headlessRule.IPCIDR = make([]string, 0, len(ipNets))
		for _, cidr := range ipNets {
			headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
		}
		return []option.HeadlessRule{
			{
				Type:           C.RuleTypeDefault,
				DefaultOptions: headlessRule,
			},
		}, nil
	}

	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		f, err := os.Open(filepath.Join(assetsDir, "geosite.db"))
		if err != nil {
			return nil, err
		}
		defer f.Close()
		reader, _, err := geosites.NewReader(f)
		if err != nil {
			return nil, err
		}
		sourceSet, err := reader.Read(name)
		if err != nil {
			return nil, fmt.Errorf("failed to read geosite code %s: %w", name, err)
		}
		compiled := geosites.Compile(sourceSet)
		var headlessRule option.DefaultHeadlessRule
		headlessRule.Domain = compiled.Domain
		headlessRule.DomainSuffix = compiled.DomainSuffix
		headlessRule.DomainKeyword = compiled.DomainKeyword
		headlessRule.DomainRegex = compiled.DomainRegex
		return []option.HeadlessRule{
			{
				Type:           C.RuleTypeDefault,
				DefaultOptions: headlessRule,
			},
		}, nil
	}
}

func buildInboundRegistry() *inbound.Registry {
	registry := inbound.NewRegistry()
	tun.RegisterInbound(registry)
	redirect.RegisterRedirect(registry)
	redirect.RegisterTProxy(registry)
	direct.RegisterInbound(registry)
	socks.RegisterInbound(registry)
	http.RegisterInbound(registry)
	mixed.RegisterInbound(registry)
	return registry
}

func buildOutboundRegistry() *outbound.Registry {
	registry := outbound.NewRegistry()
	direct.RegisterOutbound(registry)
	block.RegisterOutbound(registry)
	protocolDns.RegisterOutbound(registry)
	group.RegisterSelector(registry)
	group.RegisterURLTest(registry)
	socks.RegisterOutbound(registry)
	http.RegisterOutbound(registry)
	shadowsocks.RegisterOutbound(registry)
	vmess.RegisterOutbound(registry)
	trojan.RegisterOutbound(registry)
	tor.RegisterOutbound(registry)
	ssh.RegisterOutbound(registry)
	shadowtls.RegisterOutbound(registry)
	vless.RegisterOutbound(registry)
	anytls.RegisterOutbound(registry)
	hysteria.RegisterOutbound(registry)
	tuic.RegisterOutbound(registry)
	hysteria2.RegisterOutbound(registry)
	wireguard.RegisterOutbound(registry)
	return registry
}

func buildEndpointRegistry() *endpoint.Registry {
	registry := endpoint.NewRegistry()
	wireguard.RegisterEndpoint(registry)
	return registry
}

func buildDNSTransportRegistry() *dns.TransportRegistry {
	registry := dns.NewTransportRegistry()
	transport.RegisterTCP(registry)
	transport.RegisterUDP(registry)
	transport.RegisterTLS(registry)
	transport.RegisterHTTPS(registry)
	hosts.RegisterTransport(registry)
	local.RegisterTransport(registry)
	fakeip.RegisterTransport(registry)
	quic.RegisterTransport(registry)
	quic.RegisterHTTP3Transport(registry)
	return registry
}

func buildServiceRegistry() *service.Registry {
	return service.NewRegistry()
}

func loadCustomCerts(dir string) {
	pemBytes, err := os.ReadFile(filepath.Join(dir, "ca.pem"))
	if err != nil || len(pemBytes) == 0 {
		return
	}
	pool, err := x509.SystemCertPool()
	if err != nil || pool == nil {
		pool = x509.NewCertPool()
	}
	if pool.AppendCertsFromPEM(pemBytes) {
		systemRoots = pool
	}
}

func main() {
	// Support optional leading "run" subcommand for CLI compatibility
	args := os.Args[1:]
	if len(args) > 0 && args[0] == "run" {
		args = args[1:]
	}

	fs := flag.NewFlagSet("tsukimibox-core", flag.ExitOnError)
	configPath := fs.String("c", "", "path to sing-box config json")
	assetsPath := fs.String("a", "", "path to external assets directory (geoip.db/geosite.db)")
	workDir := fs.String("w", "", "working directory")
	appUid := fs.Int("uid", -1, "android app uid for file ownership")
	appGid := fs.Int("gid", -1, "android app gid for file ownership")
	statsListen := fs.String("stats-listen", "", "loopback address for internal traffic stats HTTP server")
	statsOutbounds := fs.String("stats-outbounds", "", "comma-separated outbound tags to track traffic stats")
	if err := fs.Parse(args); err != nil {
		log.Fatalf("parse flags: %v", err)
	}

	if *configPath == "" {
		log.Fatal("missing -c config path")
	}
	if *workDir != "" {
		if err := os.MkdirAll(*workDir, 0755); err == nil {
			if *appUid >= 0 {
				gid := *appGid
				if gid < 0 {
					gid = *appUid
				}
				_ = os.Chown(*workDir, *appUid, gid)
			}
			_ = os.Chdir(*workDir)
		}
	}
	if *assetsPath != "" {
		resourcePaths = append(resourcePaths, *assetsPath)
		registerGeoHooks(*assetsPath)
		loadCustomCerts(*assetsPath)
	}

	configBytes, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatalf("read config: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	ctx = box.Context(ctx,
		buildInboundRegistry(),
		buildOutboundRegistry(),
		buildEndpointRegistry(),
		buildDNSTransportRegistry(),
		buildServiceRegistry(),
	)
	ctx = singService.ContextWithDefaultRegistry(ctx)
	if *appUid >= 0 {
		gid := *appGid
		if gid < 0 {
			gid = *appUid
		}
		ctx = filemanager.WithDefault(ctx, *workDir, "", *appUid, gid)
	}

	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, configBytes); err != nil {
		log.Fatalf("decode config: %v", err)
	}

	instance, err := box.New(box.Options{
		Options: options,
		Context: ctx,
	})
	if err != nil {
		log.Fatalf("create sing-box instance: %v", err)
	}

	var statsServer *nethttp.Server
	if *statsListen != "" && *statsOutbounds != "" {
		rawTags := strings.Split(*statsOutbounds, ",")
		tags := make([]string, 0, len(rawTags))
		seen := make(map[string]struct{}, len(rawTags))
		for _, t := range rawTags {
			t = strings.TrimSpace(t)
			if t == "" {
				continue
			}
			if _, ok := seen[t]; ok {
				continue
			}
			seen[t] = struct{}{}
			tags = append(tags, t)
		}
		if len(tags) > 0 {
			v2api := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
				Enabled:   true,
				Outbounds: tags,
			})
			instance.Router().AppendTracker(v2api.StatsService())

			type tagTraffic struct {
				Tx int64 `json:"tx"`
				Rx int64 `json:"rx"`
			}
			mux := nethttp.NewServeMux()
			mux.HandleFunc("/stats", func(w nethttp.ResponseWriter, r *nethttp.Request) {
				res := make(map[string]tagTraffic, len(tags))
				for _, tag := range tags {
					tx := v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>uplink", tag))
					rx := v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>downlink", tag))
					res[tag] = tagTraffic{Tx: tx, Rx: rx}
				}
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(res)
			})
			ln, lnErr := net.Listen("tcp", *statsListen)
			if lnErr != nil {
				log.Printf("warning: failed to listen stats server on %s: %v", *statsListen, lnErr)
			} else {
				statsServer = &nethttp.Server{
					Handler:           mux,
					ReadHeaderTimeout: 2 * time.Second,
				}
				go func() {
					if serveErr := statsServer.Serve(ln); serveErr != nil && serveErr != nethttp.ErrServerClosed {
						log.Printf("stats server stopped: %v", serveErr)
					}
				}()
			}
		}
	}

	if err := instance.Start(); err != nil {
		if statsServer != nil {
			_ = statsServer.Close()
		}
		_ = instance.Close()
		log.Fatalf("start sing-box instance: %v", err)
	}

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	sig := <-sigCh
	log.Printf("received signal %v, shutting down sing-box root core...", sig)

	cancel()
	if statsServer != nil {
		_ = statsServer.Close()
	}
	if err := instance.Close(); err != nil {
		log.Printf("close sing-box instance: %v", err)
	}
}
