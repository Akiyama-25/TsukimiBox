package libcore

import (
	"fmt"
	"os"
	"path/filepath"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	file          *os.File
	geositeReader *geosites.Reader
}

func (g *geosite) Open(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	geositeReader, _, err := geosites.NewReader(f)
	if err != nil {
		f.Close()
		return err
	}
	g.file = f
	g.geositeReader = geositeReader
	return nil
}

func (g *geosite) Close() error {
	if g.file != nil {
		err := g.file.Close()
		g.file = nil
		return err
	}
	return nil
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geosite)
		if err := g.Open(filepath.Join(externalAssetsPath, "geosite.db")); err != nil {
			return nil, err
		}
		defer g.Close()
		return g.Rules(name)
	}
}
