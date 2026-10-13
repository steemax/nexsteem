#!/usr/bin/env python3
"""Apply independent visual branding only to a managed upstream build copy."""
# SPDX-License-Identifier: EPL-1.0
# Copyright (c) 2026 steemax.
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
COMPONENTS = Path('public/common/components')
COREUI = COMPONENTS / 'nexus-coreui-plugin'
RAPTURE = COMPONENTS / 'nexus-rapture'
UI = COMPONENTS / 'nexus-ui-plugin'
ASSETS = PROJECT / 'branding/assets'
BRAND = 'NexSteem'


def validate_svg(source):
    if '<!DOCTYPE' in source.upper() or '<!ENTITY' in source.upper():
        raise ValueError('Brand SVG must not contain a DTD or entity')
    document = ET.fromstring(source)
    if document.tag != '{http://www.w3.org/2000/svg}svg':
        raise ValueError('Brand asset must be an SVG document')
    for element in document.iter():
        name = element.tag.rsplit('}', 1)[-1].lower()
        if name in {'script', 'foreignobject', 'image', 'style', 'a', 'use'}:
            raise ValueError('Brand SVG must contain only self-contained static artwork')
        for key, value in element.attrib.items():
            if key.rsplit('}', 1)[-1].lower().startswith('on'):
                raise ValueError('Brand SVG must not contain event handlers')
            if 'href' in key.lower() or 'url(' in value.lower() or re.search(r'(?:https?:|data:|javascript:|//)', value, re.I):
                raise ValueError('Brand SVG must not contain external references')


def apply(stage):
    stage = Path(stage).resolve()
    if stage == PROJECT or stage.is_relative_to((PROJECT / 'upstream').resolve()):
        raise ValueError('Branding may only modify a build copy, never the project/upstream checkout')
    if not (stage / 'pom.xml').is_file():
        raise ValueError('Expected a prepared Nexus source tree')
    assets = {name: (ASSETS / (name + '.svg')).read_text() for name in ('header-light', 'header-dark', 'mark')}
    for source in assets.values():
        validate_svg(source)
    changes = {}

    def patch(relative, pairs):
        path = stage / relative
        source = path.read_text()
        for before, after in pairs:
            if source.count(before) != 1:
                raise ValueError(f'Branding anchor changed in {relative}: {before[:90]}')
            source = source.replace(before, after, 1)
        changes[path] = source

    def svg(relative, asset, monochrome=False):
        path = stage / relative
        original = path.read_text()
        if original.count('<svg') != 1 or original.count('</svg>') != 1:
            raise ValueError(f'Expected exactly one upstream SVG in {relative}')
        # Preserve any original copyright/license comments and XML declaration.
        prefix = original[:original.index('<svg')]
        replacement = assets[asset]
        if monochrome:
            replacement = re.sub(r'#[a-fA-F0-9]{6}', '#ffffff', replacement)
        changes[path] = prefix + replacement
        validate_svg(changes[path])

    def new_svg(relative, asset):
        path = stage / relative
        if path.exists():
            raise ValueError(f'Expected a new NexSteem asset path in {relative}')
        changes[path] = assets[asset]

    for filename, asset in [('logo-core-edition-header.svg', 'header-light'),
                            ('logo-core-edition-header-dark.svg', 'header-dark')]:
        svg(COREUI / 'src/art/logos' / filename, asset)
    resources = RAPTURE / 'src/main/resources/static/rapture/resources'
    svg(resources / 'favicon.svg', 'mark')
    svg(resources / 'favicon-white.svg', 'mark', monochrome=True)
    svg(resources / 'icons/x32/sonatype-repository-icon-reverse.svg', 'mark')
    # Main.js registers both assets as new-nexus-black; About and Help use them.
    svg(resources / 'icons/x16/sonatype-repository-icon.svg', 'mark')
    svg(resources / 'icons/x100/sonatype-repository-icon.svg', 'mark')
    svg(resources / 'images/sonatype-repository-logo-loading.svg', 'header-light')

    patch(RAPTURE / 'src/main/resources/org/sonatype/nexus/rapture/internal/index.vm', [
        ('<title>Sonatype Nexus Repository</title>', f'<title>{BRAND}</title>'),
        ('<meta name="description" content="Sonatype Nexus Repository"/>', f'<meta name="description" content="{BRAND}"/>'),
        ('<link rel="icon" type="image/png" href="$raptureUrl/resources/safari-favicon-32x32.png?$urlSuffix" sizes="32x32">',
         '<link rel="icon" type="image/svg+xml" href="$raptureUrl/resources/favicon.svg?$urlSuffix" sizes="any">'),
        ('<link rel="mask-icon" href="$raptureUrl/resources/favicon-white.svg?$urlSuffix" color="#00bb6c">',
         '<link rel="mask-icon" href="$raptureUrl/resources/favicon-white.svg?$urlSuffix" color="#2563eb">'),
        ('<link rel="icon" type="image/png" href="$raptureUrl/resources/favicon.svg?$urlSuffix" sizes="16x16">',
         '<link rel="icon" type="image/svg+xml" href="$raptureUrl/resources/favicon.svg?$urlSuffix" sizes="any">'),
        ('alt="Sonatype Nexus Repository Manager Logo"', f'alt="{BRAND} logo"')])
    patch(RAPTURE / 'src/main/java/org/sonatype/nexus/rapture/settings/RaptureSettings.java', [
        ('public static final String DEFAULT_TITLE = "Sonatype Nexus Repository";',
         f'public static final String DEFAULT_TITLE = "{BRAND}";')])
    patch(RAPTURE / 'src/main/resources/static/rapture/NX/view/header/Logo.js', [
        ("alt: 'Sonatype',", f"alt: '{BRAND}',")])
    patch(RAPTURE / 'src/main/resources/static/rapture/NX/app/PluginStrings.js', [
        ("Header_Panel_Logo_Text: 'Sonatype Nexus Repository',", f"Header_Panel_Logo_Text: '{BRAND}',"),
        ("Header_Help_About_Tooltip: 'About Sonatype Nexus Repository',", f"Header_Help_About_Tooltip: 'About {BRAND}',"),
        ("AboutWindow_Title: 'About Sonatype Nexus Repository',", f"AboutWindow_Title: 'About {BRAND}',")])
    frontend = COREUI / 'src/frontend/src/components'
    patch(frontend / 'GlobalHeader/GlobalHeader.jsx', [
        ('altText: `Sonatype Nexus Repository ${getEditionText()}`',
         f'altText: edition !== COMMUNITY && edition !== PRO ? "{BRAND}" : `Sonatype Nexus Repository ${{getEditionText()}}`')])
    patch(frontend / 'GlobalHeader/GlobalHeaderRadix.jsx', [
        ('alt={`Sonatype Nexus Repository ${getEditionText()}`}',
         f'alt={{edition !== COMMUNITY && edition !== PRO ? "{BRAND}" : `Sonatype Nexus Repository ${{getEditionText()}}`}}')])
    patch(frontend / 'login/LoginPageRadix.jsx', [
        ('aria-label="Sonatype Nexus Repository home"',
         f'aria-label={{edition !== \'COMMUNITY\' && edition !== \'PRO\' ? \'{BRAND} home\' : \'Sonatype Nexus Repository home\'}}'),
        ('const logos = LOGOS[edition] || LOGOS.PRO;', 'const logos = LOGOS[edition] || LOGOS.CORE;')])
    patch(frontend / 'login/SelfHostedLoginPageWrapper.jsx', [
        ('  coreLight: coreLogo,', f"  coreLight: coreLogo,\n  coreAltText: '{BRAND}',")])
    patch(UI / 'src/frontend/src/components/layout/LoginLayout.jsx', [
        ('aria-label={`Sonatype Nexus Repository ${getEditionText()} home`}',
         'aria-label={edition !== EDITION_COMMUNITY && edition !== EDITION_PRO && logoConfig?.coreAltText ? `${logoConfig.coreAltText} home` : `Sonatype Nexus Repository ${getEditionText()} home`}'),
        ('alt={`Sonatype Nexus Repository ${getEditionText()}`}',
         'alt={edition !== EDITION_COMMUNITY && edition !== EDITION_PRO && logoConfig?.coreAltText ? logoConfig.coreAltText : `Sonatype Nexus Repository ${getEditionText()}`}')])
    patch(frontend / 'GlobalHeader/HelpMenu.jsx', [
        ('  const version = ExtJS.useState(() => ExtJS.state().getVersion());',
         '  const version = ExtJS.useState(() => ExtJS.state().getVersion());\n  const edition = ExtJS.useState(() => ExtJS.state().getEdition());'),
        ('            Nexus Repository Manager',
         f"            {{edition !== 'COMMUNITY' && edition !== 'PRO' ? '{BRAND}' : 'Nexus Repository Manager'}}")])
    patch(RAPTURE / 'src/main/resources/static/rapture/NX/view/AboutWindow.js', [
        ("      me.down('#aboutInfo').showInfo({",
         f"      var productInfo = NX.State.getEdition() === 'CORE' ? {{'Product': '{BRAND}', 'Open Core': 'Sonatype Nexus Repository Core'}} : {{}};\n      me.down('#aboutInfo').showInfo(Ext.apply(productInfo, {{"),
        ('      });\n    };', '      }));\n    };')])
    # Bundle the wizard mark with its own frontend: it must not depend on an older
    # shared Rapture resource cached by a browser or proxy after an upgrade.
    new_svg(UI / 'src/frontend/src/art/logos/nexsteem-mark.svg', 'mark')
    patch(UI / 'src/frontend/src/components/onboarding-wizard/Welcome.tsx', [
        ("import ExtJS from '../../interface/ExtJS';",
         "import nexsteemWelcomeMark from '../../art/logos/nexsteem-mark.svg';"),
        ("function getWelcomeLogoUrl(): string {\n  try {\n    return ExtJS.urlOf('static/rapture/resources/favicon.svg');\n  } catch {\n    return '/static/rapture/resources/favicon.svg';\n  }\n}",
         'function getWelcomeLogoUrl(): string {\n  return nexsteemWelcomeMark;\n}')])
    patch(UI / 'src/frontend/src/components/onboarding-wizard/UIStrings.ts', [
        ("LOGO_ALT: 'Sonatype Nexus Repository',", f"LOGO_ALT: '{BRAND}',"),
        ("TITLE: `Let's Get You Set Up`,", f"TITLE: `Let's Set Up {BRAND}`,"),
        ("'This wizard will guide you through a few quick steps to get your Nexus Repository ready for use.'",
         f"'This wizard will guide you through a few quick steps to get your {BRAND} instance ready for use.'"),
        ("DESCRIPTION: 'Your Nexus Repository is ready to use.'", f"DESCRIPTION: 'Your {BRAND} instance is ready to use.'")])
    patch(frontend / 'pages/user/Welcome/Welcome.jsx', [
        ('        <div className="nxrm-welcome">',
         f'        <div className="nxrm-welcome">\n          {{status?.edition === \'CORE\' && <h1>{BRAND} Dashboard</h1>}}')])
    patch(UI / 'src/frontend/src/components/preview/pages/Welcome/Welcome.jsx', [
        ('<Heading as="h1" size="6" mb="4">Dashboard</Heading>',
         f'<Heading as="h1" size="6" mb="4">{{iframeProps.edition === \'CORE\' ? \'{BRAND} Dashboard\' : \'Dashboard\'}}</Heading>')])
    patch(UI / 'src/frontend/src/components/preview/pages/Welcome/WelcomeDashboard.tsx', [
        ("if (!edition) return 'Nexus Repository';", f"if (!edition) return '{BRAND}';"),
        ("if (upper.includes('OSS') || upper.includes('CORE')) return 'Core Edition';",
         f"if (upper.includes('OSS') || upper.includes('CORE')) return '{BRAND}';"),
        (": 'Welcome to Nexus Repository';",
         f": status?.edition === 'CORE' ? 'Welcome to {BRAND}' : 'Welcome to Nexus Repository';")])
    # Validate all paths/anchors first; a future upstream change cannot leave a partially branded tree.
    for path, content in changes.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    return [str(path.relative_to(stage)) for path in changes]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    for path in apply(args.stage):
        print('Branding overlay: ' + path)


if __name__ == '__main__':
    main()
