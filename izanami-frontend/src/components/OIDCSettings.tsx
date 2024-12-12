import { useState } from "react";
import { OIDCSettings, TRights } from "../utils/types";
import { Form } from "./Form";
import { RightSelector } from "./RightSelector";
import { Modal } from "./Modal";
import { fetchOpenIdConnectConfiguration } from "../utils/queries";

export function OIDCSettingsForm(props: {
  defaultValue?: OIDCSettings;
  onSubmit: (value: OIDCSettings | undefined) => void;
}) {

  const [isOIDCModalOpened, setOIDCModal] = useState<boolean>(false);
  const [oidcConfig, updateOidcConfig] = useState<OIDCSettings | undefined>(
    props.defaultValue
  );

  const fetchConfig = ({ oidcUrl }: { oidcUrl: string }) => {
    return fetchOpenIdConnectConfiguration(oidcUrl)
      .then(result => {
        updateOidcConfig(result)
        setOIDCModal(false)
      })
      .catch(err => {
        setOIDCModal(false)
      })
  };

  return <div className="sub_container">
    <button type="button" className="btn btn-success me-2" onClick={() => setOIDCModal(true)}>
      Get from OIDC config
    </button>
    <Modal
      title="Get from OIDC config"
      visible={isOIDCModalOpened}
      onClose={() => setOIDCModal(false)}
    >
      <Form
        submitText="Fetch configuration"
        schema={{
          oidcUrl: {
            label: 'URL of the OIDC config',
            type: 'string',
            defaultValue: 'https://accounts.google.com/.well-known/openid-configuration'
          }
        }}
        onSubmit={fetchConfig}
      />
    </Modal>
    <Form
      value={oidcConfig}
      schema={{
        enabled: {
          label: "Enabled OIDC authentication",
          type: 'bool',
          defaultValue: false
        },
        name: {
          label: "Name",
          type: "string",
          required: true
        },
        clientId: {
          label: "Client ID",
          type: 'string',
          required: true
        },
        clientSecret: {
          label: "Client Secret",
          type: 'string',
          required: true
        },
        tokenUrl: {
          label: "Token URL",
          type: 'string',
          required: true
        },
        authorizeUrl: {
          label: "Authorize URL",
          type: 'string',
          required: true
        },
        scopes: {
          label: "Scopes",
          type: 'string',
          required: true,
        },
        accessTokenField: {
          label: "Access token field name",
          type: 'string',
          required: true
        },
        nameField: {
          label: "Name field name",
          type: 'string',
          required: true
        },
        emailField: {
          label: "Email field name",
          type: 'string',
          required: true
        },
        callbackUrl: {
          label: "Callback URL",
          type: 'string',
          required: true
        },
        // userInfoUrl: {
        //   label: "User Info URL",
        //   type: 'string'
        // },
        // introspectionUrl: {
        //   label: "Introspection URL",
        //   type: 'string'
        // },
        // loginUrl: {
        //   label: "Login URL",
        //   type: 'string'
        // },
        // logoutUrl: {
        //   label: "Logout URL",
        //   type: 'string'
        // },
        claims: {
          label: "Claims",
          type: 'string'
        },
        'pkce.enabled': {
          label: "Use PKCE flow",
          type: 'bool',
          defaultValue: false
        },
        'pkce.algorithm': {
          label: "PKCE Algorithm",
          type: 'string',
          format: 'select',
          defaultValue: "S256",
          visible: ({ rawValues }) => rawValues.pkce?.enabled,
          options: [
            { value: 'S256', label: 'HMAC-SHA256' },
            { value: 'plain', label: 'PLAIN (not recommended)' },
          ]
        },
        sessionMaxAge: {
          label: "Session max. age",
          type: 'number',
          defaultValue: 86400,
        },
        defaultOIDCUserRights: {
          label: "Default ODIC User Rights",
          type: "object",
          array: true,
          render: ({ onChange }) => <RightSelector
            defaultValue={props.defaultValue?.defaultOIDCUserRights as TRights | undefined}
            tenantLevelFilter="Admin"
            onChange={(v) => {
              onChange?.(v);
            }}
          />,
        }
      }}
      // footer={({ valid, reset }) => null}
      onSubmit={value => Promise.resolve(props.onSubmit(value))}
    />
  </div>
}
