#!/usr/bin/env python3
"""Add open-provider settings to the public OAuth UI in the managed build copy."""
import sys
from pathlib import Path

root = Path(sys.argv[1]) / "public/common/components/nexus-coreui-plugin/src/frontend/src/components/pages/admin/OAuth2Configuration"
page = root / "OAuth2Configuration.jsx"
source = page.read_text()
anchor = '          <NxFieldset label={LABELS.CLAIM_MAPPINGS} isRequired>'
if source.count(anchor) != 1:
    raise SystemExit("OAuth form structure changed; review the open OIDC overlay")
fields = '''          <NxFieldset label="OpenID Connect Provider">
            <NxFormGroup label="Issuer URL" sublabel="The issuer in the provider discovery document. For Keycloak it can be inferred from the Authorization URL.">
              <NxTextInput className="nx-text-input--long" {...FormUtils.fieldProps('issuer', current)}
                onChange={FormUtils.handleUpdate('issuer', send)} />
            </NxFormGroup>
            <NxFormGroup label="Access token audience" sublabel="Defaults to Client ID. Must match the audience assigned to Nexus access tokens.">
              <NxTextInput {...FormUtils.fieldProps('audience', current)} onChange={FormUtils.handleUpdate('audience', send)} />
            </NxFormGroup>
            <NxFormGroup label="Nexus public URL" sublabel="The public base URL of this Nexus. Register its /oidc/callback URL in Keycloak.">
              <NxTextInput className="nx-text-input--long" {...FormUtils.fieldProps('publicUrl', current)}
                onChange={FormUtils.handleUpdate('publicUrl', send)} />
            </NxFormGroup>
            <NxFormGroup label="Group to Nexus role mappings" sublabel='An explicit JSON object, for example {"reader":"nx-anonymous"}. Unmapped groups grant no roles.'>
              <NxTextInput type="textarea" className="nx-text-input--long" {...FormUtils.fieldProps('roleMapping', current)}
                onChange={FormUtils.handleUpdate('roleMapping', send)} />
            </NxFormGroup>
          </NxFieldset>

'''
page.write_text(source.replace(anchor, fields + anchor))
machine = root / "OAuth2ConfigurationMachine.js"
source = machine.read_text()
source = source.replace('exactMatchClaims: stringifyObject(data.exactMatchClaims),',
                        'exactMatchClaims: stringifyObject(data.exactMatchClaims),\n              roleMapping: stringifyObject(data.roleMapping),')
source = source.replace('exactMatchClaims: parseString(exactMatchClaims),',
                        'exactMatchClaims: parseString(exactMatchClaims),\n            roleMapping: parseString(data.roleMapping),')
machine.write_text(source)
