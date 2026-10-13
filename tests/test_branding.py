# SPDX-License-Identifier: EPL-1.0
# Copyright (c) 2026 steemax.
import hashlib
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('branding', ROOT / 'build-support/branding-overlay.py')
branding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(branding)


class BrandingOverlayTest(unittest.TestCase):
    def setUp(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='branding-test-', dir=ROOT / 'build')
        self.stage = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
        core = branding.COREUI
        rapture = branding.RAPTURE
        ui = branding.UI
        self.paths = [Path('pom.xml'),
            core / 'src/art/logos/logo-core-edition-header.svg',
            core / 'src/art/logos/logo-core-edition-header-dark.svg',
            rapture / 'src/main/resources/static/rapture/resources/favicon.svg',
            rapture / 'src/main/resources/static/rapture/resources/favicon-white.svg',
            rapture / 'src/main/resources/static/rapture/resources/icons/x32/sonatype-repository-icon-reverse.svg',
            rapture / 'src/main/resources/static/rapture/resources/icons/x16/sonatype-repository-icon.svg',
            rapture / 'src/main/resources/static/rapture/resources/icons/x100/sonatype-repository-icon.svg',
            rapture / 'src/main/resources/static/rapture/resources/images/sonatype-repository-logo-loading.svg',
            rapture / 'src/main/resources/org/sonatype/nexus/rapture/internal/index.vm',
            rapture / 'src/main/java/org/sonatype/nexus/rapture/settings/RaptureSettings.java',
            rapture / 'src/main/resources/static/rapture/NX/view/header/Logo.js',
            rapture / 'src/main/resources/static/rapture/NX/app/PluginStrings.js',
            core / 'src/frontend/src/components/GlobalHeader/GlobalHeader.jsx',
            core / 'src/frontend/src/components/GlobalHeader/GlobalHeaderRadix.jsx',
            core / 'src/frontend/src/components/GlobalHeader/HelpMenu.jsx',
            rapture / 'src/main/resources/static/rapture/NX/view/AboutWindow.js',
            rapture / 'src/main/resources/static/rapture/NX/controller/Main.js',
            core / 'src/frontend/src/components/login/LoginPageRadix.jsx',
            core / 'src/frontend/src/components/login/SelfHostedLoginPageWrapper.jsx',
            ui / 'src/frontend/src/components/layout/LoginLayout.jsx',
            ui / 'src/frontend/src/components/onboarding-wizard/Welcome.tsx',
            ui / 'src/frontend/src/components/onboarding-wizard/UIStrings.ts',
            core / 'src/frontend/src/components/pages/user/Welcome/Welcome.jsx',
            ui / 'src/frontend/src/components/preview/pages/Welcome/Welcome.jsx',
            ui / 'src/frontend/src/components/preview/pages/Welcome/WelcomeDashboard.tsx',
            *[ui / 'src/frontend/src/components/onboarding-wizard' / name for name in (
                'Wizard.tsx', 'wizardMachine.ts', 'stepRegistry.ts', 'changePasswordMachine.ts',
                'ChangePasswordStep.tsx', 'AnonymousAccessStep.tsx', 'OnboardingWizardMount.tsx',
                'steps/EulaStep/EulaStep.tsx', 'steps/EulaStep/eulaMachine.ts')],

            Path('public/common/assemblies/nexus-base-overlay/src/main/resources/overlay/NOTICE.txt'),
            rapture / 'src/main/resources/org/sonatype/nexus/rapture/internal/COPYRIGHT.vm',
            *[core / 'src/art/logos' / ('logo-' + edition + '-edition-header' + theme + '.svg')
              for edition in ('community', 'pro') for theme in ('', '-dark')]]
        for relative in self.paths:
            destination = self.stage / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / 'upstream/nexus-public' / relative, destination)

    def snapshot(self):
        return {str(p.relative_to(self.stage)): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in self.stage.rglob('*') if p.is_file()}

    def test_overlay_is_scoped_and_preserves_copyright_and_notice(self):
        original = {p: (self.stage / p).read_text() for p in self.paths}
        changed = branding.apply(self.stage)
        self.assertEqual(25, len(changed))
        for relative, text in original.items():
            after = (self.stage / relative).read_text()
            if str(relative) in changed:
                if '<svg' in text:
                    self.assertTrue(after.startswith(text[:text.index('<svg')]))
                elif text.startswith('/*'):
                    self.assertEqual(text[:text.index('*/') + 2], after[:after.index('*/') + 2])
                elif text.startswith('#*'):
                    self.assertEqual(text[:text.index('*#') + 2], after[:after.index('*#') + 2])
            else:
                self.assertEqual(text, after)
        index = self.stage / branding.RAPTURE / 'src/main/resources/org/sonatype/nexus/rapture/internal/index.vm'
        self.assertIn('<title>NexSteem</title>', index.read_text())
        self.assertNotIn('href="$raptureUrl/resources/safari-favicon-32x32.png', index.read_text())
        for relative in changed:
            self.assertNotIn('Nexus Core Extended', (self.stage / relative).read_text())
            if relative.endswith('.svg'):
                branding.validate_svg((self.stage / relative).read_text())

    def test_active_logo_consumers_and_about_credit_remain_connected(self):
        branding.apply(self.stage)
        core = self.stage / branding.COREUI
        resources = self.stage / branding.RAPTURE / 'src/main/resources/static/rapture/resources'
        # Both current headers and both login implementations resolve to the replaced Core SVGs.
        for relative in ('GlobalHeader/GlobalHeader.jsx', 'GlobalHeader/GlobalHeaderRadix.jsx',
                         'login/LoginPageRadix.jsx', 'login/SelfHostedLoginPageWrapper.jsx'):
            source = (core / 'src/frontend/src/components' / relative).read_text()
            self.assertIn('art/logos/logo-core-edition-header.svg', source)
        for filename in ('logo-core-edition-header.svg', 'logo-core-edition-header-dark.svg'):
            source = (core / 'src/art/logos' / filename).read_text()
            self.assertIn('<title id="brand-title">NexSteem</title>', source)
            self.assertIn('>NexSteem</text>', source)
        self.assertIn('LOGOS[edition] || LOGOS.CORE',
                      (core / 'src/frontend/src/components/login/LoginPageRadix.jsx').read_text())
        # The icon registry is untouched; both sizes it registers now carry our own mark.
        registry = (self.stage / branding.RAPTURE / 'src/main/resources/static/rapture/NX/controller/Main.js').read_text()
        self.assertIn("file: 'sonatype-repository-icon.svg'", registry)
        self.assertIn("variants: ['x16', 'x100']", registry)
        for folder in ('x16', 'x100'):
            self.assertIn('>NexSteem</title>', (resources / 'icons' / folder / 'sonatype-repository-icon.svg').read_text())
        about = (self.stage / branding.RAPTURE / 'src/main/resources/static/rapture/NX/view/AboutWindow.js').read_text()
        self.assertIn("'Product': 'NexSteem'", about)
        self.assertIn("'Open Core': 'Sonatype Nexus Repository Core'", about)
        self.assertIn("html: NX.Icons.img('new-nexus-black', 'x100')", about)
        self.assertIn("src: NX.util.Url.urlOf('/COPYRIGHT.html')", about)
        self.assertIn("title: NX.I18n.get('AboutWindow_About_Title')", about)
        self.assertEqual((ROOT / 'branding/assets/header-light.svg').read_bytes(),
                         (ROOT / 'docs/images/logo.svg').read_bytes())

    def test_wizard_product_artwork_changes_without_altering_setup_or_legal_flow(self):
        base = branding.UI / 'src/frontend/src/components/onboarding-wizard'
        before_welcome = (self.stage / base / 'Welcome.tsx').read_text()
        before_strings = (self.stage / base / 'UIStrings.ts').read_text()
        originals = {p: (self.stage / p).read_bytes() for p in self.paths
                     if str(p).startswith(str(base)) and p.name not in ('Welcome.tsx', 'UIStrings.ts')}
        branding.apply(self.stage)
        welcome = (self.stage / base / 'Welcome.tsx').read_text()
        self.assertIn("import nexsteemWelcomeMark from '../../art/logos/nexsteem-mark.svg';", welcome)
        self.assertIn('return nexsteemWelcomeMark;', welcome)
        self.assertNotIn('resources/favicon.svg', welcome)
        artwork = self.stage / branding.UI / 'src/frontend/src/art/logos/nexsteem-mark.svg'
        self.assertEqual((ROOT / 'branding/assets/mark.svg').read_bytes(), artwork.read_bytes())
        self.assertEqual(before_welcome[before_welcome.index('export default function Welcome'):],
                         welcome[welcome.index('export default function Welcome'):])
        strings = (self.stage / base / 'UIStrings.ts').read_text()
        self.assertIn("LOGO_ALT: 'NexSteem'", strings)
        self.assertIn("TITLE: `Let's Set Up NexSteem`", strings)
        self.assertIn('get your NexSteem instance ready for use.', strings)
        self.assertIn('Your NexSteem instance is ready to use.', strings)
        self.assertEqual(before_strings[before_strings.index('    COMMUNITY_EULA:'):],
                         strings[strings.index('    COMMUNITY_EULA:'):])
        for relative, original in originals.items():
            self.assertEqual(original, (self.stage / relative).read_bytes())

    def test_future_anchor_change_fails_before_any_file_is_written(self):
        index = self.stage / branding.RAPTURE / 'src/main/resources/org/sonatype/nexus/rapture/internal/index.vm'
        index.write_text(index.read_text().replace('<title>Sonatype Nexus Repository</title>', '<title>Changed upstream title</title>'))
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            branding.apply(self.stage)
        self.assertEqual(before, self.snapshot())

    def test_rejects_upstream_destination_and_executable_or_external_svg(self):
        with self.assertRaisesRegex(ValueError, 'never the project/upstream'):
            branding.apply(ROOT / 'upstream/nexus-public')
        prefix = '<svg xmlns="http://www.w3.org/2000/svg">'
        for body in ('<script>bad()</script>', '<foreignObject/>', '<image href="https://example.org/logo.svg"/>',
                     '<path onload="bad()"/>', '<path fill="url(https://example.org/a.svg)"/>'):
            with self.assertRaises(ValueError):
                branding.validate_svg(prefix + body + '</svg>')


if __name__ == '__main__':
    unittest.main()
