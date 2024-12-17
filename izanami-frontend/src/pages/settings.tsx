import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import queryClient from "../queryClient";
import { JsonViewer } from "@textea/json-viewer";
import { Modal } from "../components/Modal";
import Select from "react-select";
import * as yup from "yup";

import {
  MutationNames,
  queryStats,
  queryConfiguration,
  updateConfiguration,
  fetchOpenIdConnectConfiguration,
} from "../utils/queries";
import { Configuration } from "../utils/types";
import { customStyles } from "../styles/reactSelect";
import { Form } from "../components/Form";
import { Loader } from "../components/Loader";
import {
  RightSelector,
  rightStateArrayToBackendMap,
} from "../components/RightSelector";
import {
  Controller,
  FormProvider,
  useForm,
  useFormContext,
} from "react-hook-form";
import { Tooltip } from "../components/Tooltip";
import { ErrorMessage } from "../components/HookFormErrorMessage";

const MAILER_OPTIONS = [
  { label: "MailJet", value: "MailJet" },
  { label: "MailGun", value: "MailGun" },
  { label: "SMTP", value: "SMTP" },
  {
    label: "Console (display mails in Izanami logs)",
    value: "Console",
  },
];

// TODO centralize this in utils package
export function yupValidationToStringError(
  yupValidation: () => any,
  error: string
): string | null {
  try {
    yupValidation();
    return null;
  } catch (e) {
    return error;
  }
}
type StatsResultStatus =
  | { state: "SUCCESS"; results: any }
  | { state: "ERROR"; error: string }
  | { state: "PENDING" }
  | { state: "INITIAL" };

export function Settings() {
  const configurationQuery = useQuery({
    queryKey: [MutationNames.CONFIGURATION],
    queryFn: () => queryConfiguration(),
  });
  const configurationMutationQuery = useMutation({
    mutationFn: (data: Omit<Configuration, "version">) =>
      updateConfiguration(data),
  });

  const updateSettings = (current: Configuration, newValue: Configuration) => {
    const wasAnonymousReportingDisabled =
      !newValue.anonymousReporting && current.anonymousReporting;

    const newDefaultRights = newValue.oidcConfiguration?.defaultOIDCUserRights;
    const backendRights = rightStateArrayToBackendMap(
      Array.isArray(newDefaultRights) ? newDefaultRights : []
    );

    return configurationMutationQuery
      .mutateAsync({
        invitationMode: newValue.invitationMode,
        originEmail: newValue.originEmail,
        anonymousReporting: newValue.anonymousReporting,
        anonymousReportingLastAsked: wasAnonymousReportingDisabled
          ? new Date()
          : current.anonymousReportingLastAsked,
        oidcConfiguration: {
          ...(newValue.oidcConfiguration || {}),
          defaultOIDCUserRights: backendRights,
        },
        mailerConfiguration: newValue.mailerConfiguration,
      })
      .then(() => {
        queryClient.invalidateQueries(MutationNames.CONFIGURATION);
      });
  };

  if (configurationQuery.isLoading) {
    return <Loader message="Loading configuration..." />;
  } else if (configurationQuery.data) {
    return (
      <>
        <h1>Global settings</h1>
        <ConfigurationForm
          configuration={configurationQuery.data}
          onSubmit={(newConfig) => {
            updateSettings(configurationQuery.data, newConfig);
          }}
        />
      </>
    );
  } else {
    return <div>Failed to load configuration</div>;
  }
}

const INVITATION_MODE_OPTIONS = [
  {
    value: "Response",
    label:
      "Request response (invitation url will be displayed right after creating invitation)",
  },
  {
    value: "Mail",
    label: "By mail (invitation url will be sent by mail to the new user)",
  },
] as const;

function ConfigurationForm(props: {
  configuration: Configuration;
  onSubmit: (conf: Configuration) => void;
}) {
  const [resultStats, setResultStats] = useState<StatsResultStatus>({
    state: "INITIAL",
  });

  const { configuration } = props;
  const defaultValue = {
    oidcConfiguration: {
      scopes: "email profile",
      usernameField: "name",
      emailField: "email",
    },
  };

  const methods = useForm<
    Omit<Omit<Configuration, "version">, "anonymousReportingLastAsked">
  >({
    values: configuration,
    defaultValues: defaultValue,
  });
  const {
    handleSubmit,
    register,
    control,
    watch,
    formState: { errors },
    setError,
  } = methods;

  const mailer = watch("mailerConfiguration.mailer");
  return (
    <FormProvider {...methods}>
      <form
        style={{ maxWidth: "700px" }}
        className="container m-0 fieldset-form"
        onSubmit={handleSubmit((data) => {
          let valid = true;
          const isEmailValid = yup
            .string()
            .email()
            .isValidSync(data.originEmail);
          if (data.originEmail && !isEmailValid) {
            valid = false;
            setError("originEmail", {
              type: "string",
              message: "Invalid email",
            });
          }
          if (valid) {
            props?.onSubmit(data);
          }
        })}
      >
        <label className="row mt-3">
          <div className="col-12">
            Mail provider
            <Controller
              name="mailerConfiguration.mailer"
              control={control}
              render={({ field }) => (
                <Select
                  value={MAILER_OPTIONS.find(
                    ({ value }) => value === field.value
                  )}
                  onChange={(e) => {
                    field.onChange(e?.value);
                  }}
                  styles={customStyles}
                  options={MAILER_OPTIONS}
                />
              )}
            />
            <ErrorMessage errors={errors} name="mailerConfiguration.mailer" />
          </div>
        </label>
        {mailer === "MailJet" && <MailJetFormV2 />}
        {mailer === "MailGun" && <MailGunFormV2 />}
        {mailer === "SMTP" && <SMTPFormV2 />}
        <label className="row  mt-3">
          <div className="col-12">
            Invitation mode
            <Controller
              name="invitationMode"
              control={control}
              render={({ field }) => (
                <Select
                  value={INVITATION_MODE_OPTIONS.find(
                    ({ value }) => value === field.value
                  )}
                  onChange={(e) => {
                    field.onChange(e?.value);
                  }}
                  styles={customStyles}
                  options={INVITATION_MODE_OPTIONS}
                />
              )}
            />
          </div>
        </label>
        <div className="row mt-3">
          <label className="col-12">
            Origin email
            <input
              className="form-control"
              type="text"
              {...register("originEmail")}
            />
          </label>
          <ErrorMessage errors={errors} name="originEmail" />
        </div>
        <div className="row mt-3">
          <div className="col-12">
            <label htmlFor="anonymousReporting">
              Anonymous reporting
              <Tooltip id="reporting-tooltip">
                This feature allows Izanami to send us periodical reports.
                <br />
                It won't send sensitive or personal data, only a set of usage
                statistics related to Izanami.
              </Tooltip>
            </label>
            <button
              id="stats-button"
              className="btn btn-secondary btn-sm ms-2"
              type="button"
              onClick={() => {
                queryStats()
                  .then((r) =>
                    setResultStats({
                      state: "SUCCESS",
                      results: r,
                    })
                  )
                  .catch(() => {
                    setResultStats({
                      state: "ERROR",
                      error: "There was an error fetching data",
                    });
                  });
              }}
            >
              Show data
            </button>
          </div>
          <div className="col-12">
            <input
              {...register("anonymousReporting")}
              id="anonymousReporting"
              type="checkbox"
              className="izanami-checkbox"
              style={{ marginTop: 0 }}
            />
          </div>
        </div>
        <OIDCForm />
        <div className="d-flex justify-content-end">
          <button type="submit" className="btn btn-primary mt-3">
            Update configuration
          </button>
        </div>
      </form>
      {resultStats.state !== "INITIAL" && (
        <Modal
          title={"Anonymous statistics"}
          visible={true}
          onClose={() => setResultStats({ state: "INITIAL" })}
          style={{ maxHeight: "50vh", overflowY: "auto" }}
        >
          {resultStats.state === "PENDING" ? (
            <Loader message="Loading Statistics" />
          ) : resultStats.state === "ERROR" ? (
            <div>{resultStats.error}</div>
          ) : (
            <JsonViewer
              rootName={false}
              value={resultStats.results}
              displayDataTypes={false}
              displaySize={false}
              theme="dark"
            />
          )}
        </Modal>
      )}
    </FormProvider>
  );
}

function MailJetFormV2() {
  const {
    register,
    formState: { errors },
  } = useFormContext<Configuration>();

  return (
    <>
      <fieldset>
        <legend>Mailjet configuration</legend>
        <div className="row">
          <label className="col-12">
            API key
            <input
              className="form-control"
              type="text"
              {...register("mailerConfiguration.apiKey", {
                required: "API key is required",
              })}
            />
          </label>
          <ErrorMessage errors={errors} name="mailerConfiguration.apiKey" />
        </div>
        <div className="row mt-3">
          <label className="col-12">
            Secret
            <input
              className="form-control"
              type="text"
              {...register("mailerConfiguration.secret", {
                required: "Secret is required",
              })}
            />
          </label>
          <ErrorMessage errors={errors} name="mailerConfiguration.secret" />
        </div>
      </fieldset>
    </>
  );
}

const MAILGUN_REGIONS_OPTIONS = [
  {
    label: "US",
    value: "US",
  },
  {
    label: "Europe",
    value: "EUROPE",
  },
] as const;

function MailGunFormV2() {
  const {
    register,
    formState: { errors },
    control,
  } = useFormContext<Configuration>();

  return (
    <>
      <fieldset>
        <legend>MailGun configuration</legend>
        <div className="row">
          <label className="col-10">
            API key
            <input
              className="form-control"
              type="text"
              {...register("mailerConfiguration.apiKey", {
                required: "API key is required",
              })}
            />
          </label>
          <ErrorMessage errors={errors} name="mailerConfiguration.apiKey" />
        </div>
        <div className="row mt-3">
          <label className="col-10">
            Region
            <Controller
              name="mailerConfiguration.region"
              control={control}
              rules={{
                required: "Region is required",
              }}
              render={({ field }) => (
                <Select
                  value={MAILGUN_REGIONS_OPTIONS.find(
                    ({ value }) => value === field.value
                  )}
                  onChange={(e) => {
                    field.onChange(e?.value);
                  }}
                  styles={customStyles}
                  options={MAILGUN_REGIONS_OPTIONS}
                />
              )}
            />
          </label>
          <ErrorMessage errors={errors} name="mailerConfiguration.region" />
        </div>
      </fieldset>
    </>
  );
}

function SMTPFormV2() {
  const {
    register,
    formState: { errors },
    control,
  } = useFormContext<Configuration>();
  return (
    <fieldset>
      <legend>SMTP configuration</legend>
      <div className="row">
        <label className="col-12">
          Host
          <input
            className="form-control"
            type="text"
            {...register("mailerConfiguration.host", {
              required: "Host is required",
            })}
          />
        </label>
        <ErrorMessage errors={errors} name="mailerConfiguration.host" />
      </div>
      <div className="row mt-3">
        <label className="col-12">
          Port
          <input
            className="form-control"
            type="number"
            {...register("mailerConfiguration.port", {
              required: "Port is required",
            })}
          />
        </label>
        <ErrorMessage errors={errors} name="mailerConfiguration.port" />
      </div>
      <div className="row mt-3">
        <label className="col-12">
          User
          <input
            className="form-control"
            type="text"
            {...register("mailerConfiguration.user")}
          />
        </label>
      </div>
      <div className="row mt-3">
        <label className="col-12">
          Password
          <input
            className="form-control"
            type="text"
            {...register("mailerConfiguration.password")}
          />
        </label>
      </div>
      <div className="row mt-3">
        <label className="col-12">
          Auth
          <input
            className="izanami-checkbox"
            type="checkbox"
            {...register("mailerConfiguration.auth")}
          />
        </label>
      </div>
      <div className="row mt-3">
        <label className="col-12">
          StartTLS enabled
          <input
            className="izanami-checkbox"
            type="checkbox"
            {...register("mailerConfiguration.starttlsEnabled")}
          />
        </label>
      </div>
      <div className="row mt-3">
        <label className="col-12">
          SMTPS
          <input
            className="izanami-checkbox"
            type="checkbox"
            {...register("mailerConfiguration.smtps")}
          />
        </label>
      </div>
    </fieldset>
  );
}

const PKCE_ALGORITHM_OPTIONS = [
  { value: "S256", label: "HMAC-SHA256" },
  { value: "plain", label: "PLAIN (not recommended)" },
] as const;

const OIDC_METHOD_OPTIONS = [
  { value: "POST", label: "POST" },
  { value: "BASIC", label: "BASIC" },
] as const;

function OIDCForm() {
  const [OIDCModlaState, setOIDCModal] = useState<
    { visible: true; error?: string } | { visible: false }
  >({ visible: false });
  const isOIDCModalOpened = OIDCModlaState.visible;

  const {
    register,
    formState: { errors },
    setValue,
    getValues,
    control,
    watch,
  } = useFormContext<Configuration>();

  const fetchConfig = ({ oidcUrl }: { oidcUrl: string }) => {
    return fetchOpenIdConnectConfiguration(oidcUrl)
      .then((result) => {
        const { method, authorizeUrl, callbackUrl, scopes, tokenUrl } =
          result as any;

        setValue("oidcConfiguration.authorizeUrl", authorizeUrl);
        setValue("oidcConfiguration.callbackUrl", callbackUrl);
        setValue("oidcConfiguration.scopes", scopes);
        setValue("oidcConfiguration.tokenUrl", tokenUrl);
        setValue("oidcConfiguration.method", method);

        setOIDCModal({ visible: false });
      })
      .catch((err) =>
        setOIDCModal({
          visible: true,
          error: "Failed to retrieve configuration",
        })
      );
  };

  const isOIDCEnabled = watch("oidcConfiguration.enabled");
  const isPKCEEnabled = watch("oidcConfiguration.pkce.enabled");

  const preventOAuthModification = getValues("preventOAuthModification");

  return (
    <>
      <Modal
        title="Get from OIDC config"
        visible={isOIDCModalOpened}
        onClose={() => setOIDCModal({ visible: false })}
      >
        <Form
          submitText="Fetch configuration"
          schema={{
            oidcUrl: {
              label: "URL of the OIDC config",
              type: "string",
              defaultValue:
                "https://identity-provider-tenant.eu.auth0.com/.well-known/openid-configuration",
            },
          }}
          onSubmit={fetchConfig}
        />
      </Modal>

      <h2 className="mt-3">OIDC</h2>

      <div className="accordion accordion-darker mt-3" id={`oidc-accordion`}>
        <div className="accordion-item">
          <h3 className="accordion-header">
            <button
              className="accordion-button collapsed"
              type="button"
              data-bs-toggle="collapse"
              data-bs-target="#oidc-accordion-collapse"
              aria-expanded="true"
              aria-controls="oidc-accordion-collapse"
            >
              Configuration{" "}
              <span
                className={`badge rounded-pill text-bg-${
                  isOIDCEnabled ? "success" : "danger"
                } ms-2`}
              >
                {isOIDCEnabled ? "ENABLED" : "DISABLED"}
              </span>
              &nbsp;
              <ErrorMessage errors={errors} name="oidcConfiguration" />
            </button>
          </h3>
          <div
            className="accordion-collapse collapse"
            aria-labelledby="headingOne"
            data-bs-parent="#oidc-accordion"
            id="oidc-accordion-collapse"
          >
            <div className="accordion-body">
              {preventOAuthModification && (
                <p className="error-message d-flex align-items-center gap-2">
                  <i className="fas fa-warning" />
                  The configuration is loaded from your environment variables
                  and cannot be edited until you remove them.
                </p>
              )}
              <fieldset disabled={preventOAuthModification}>
                <div className="row">
                  <label>
                    Enable OIDC authentication
                    <input
                      type="checkbox"
                      className="izanami-checkbox"
                      {...register("oidcConfiguration.enabled")}
                    />
                  </label>
                </div>
                {isOIDCEnabled && (
                  <>
                    <div className="row mt-3">
                      <div>
                        <button
                          type="button"
                          className="btn btn-success me-2"
                          onClick={() => setOIDCModal({ visible: true })}
                        >
                          Read OIDC config from URL
                        </button>
                      </div>
                    </div>
                    <div className="row mt-3">
                      <label>
                        Method
                        <Controller
                          name="oidcConfiguration.method"
                          control={control}
                          rules={{
                            required: "PKCE algorithm is required",
                          }}
                          render={({ field }) => (
                            <Select
                              value={OIDC_METHOD_OPTIONS.find(
                                ({ value }) => value === field.value
                              )}
                              onChange={(e) => {
                                field.onChange(e?.value);
                              }}
                              styles={customStyles}
                              options={OIDC_METHOD_OPTIONS}
                            />
                          )}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.method"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Client ID
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.clientId", {
                            required: "Client id is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.clientId"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Client Secret
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.clientSecret", {
                            required: "Client secret is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.clientSecret"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Token URL
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.tokenUrl", {
                            required: "Token URL is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.tokenUrl"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Authorize URL
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.authorizeUrl", {
                            required: "Authorize URL is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.authorizeUrl"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Scopes
                        <Tooltip id="oidc-scopes">
                          Space separated list of scopes
                        </Tooltip>
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.scopes", {
                            required: "Scopes are required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.scopes"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Name field name
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.nameField", {
                            required: "Name field is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.nameField"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Email field name
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.emailField", {
                            required: "Email field is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.emailField"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Callback URL
                        <input
                          type="text"
                          className="form-control"
                          {...register("oidcConfiguration.callbackUrl", {
                            required: "Callback url is required",
                          })}
                        />
                      </label>
                      <ErrorMessage
                        errors={errors}
                        name="oidcConfiguration.callbackUrl"
                      />
                    </div>
                    <div className="row mt-3">
                      <label>
                        Use PKCE flow
                        <input
                          type="checkbox"
                          className="izanami-checkbox"
                          {...register("oidcConfiguration.pkce.enabled")}
                        />
                      </label>
                    </div>
                    {isPKCEEnabled && (
                      <div className="row mt-3">
                        <label>
                          PKCE Algorithm
                          <Controller
                            name="oidcConfiguration.pkce.algorithm"
                            control={control}
                            rules={{
                              required: "PKCE algorithm is required",
                            }}
                            render={({ field }) => (
                              <Select
                                value={PKCE_ALGORITHM_OPTIONS.find(
                                  ({ value }) => value === field.value
                                )}
                                onChange={(e) => {
                                  field.onChange(e?.value);
                                }}
                                styles={customStyles}
                                options={PKCE_ALGORITHM_OPTIONS}
                              />
                            )}
                          />
                        </label>
                        <ErrorMessage
                          errors={errors}
                          name="oidcConfiguration.pkce.algorithm"
                        />
                      </div>
                    )}
                  </>
                )}
              </fieldset>
            </div>
          </div>
        </div>
      </div>

      <div className="accordion accordion-darker mt-3" id={`oidc-accordion`}>
        <div className="accordion-item">
          <h3 className="accordion-header">
            <button
              className="accordion-button collapsed"
              type="button"
              data-bs-toggle="collapse"
              data-bs-target="#default-oidc-rights-accordion-collapse"
              aria-expanded="true"
              aria-controls="default-oidc-rights-accordion-collapse"
            >
              Default rights &nbsp;
              <ErrorMessage errors={errors} name="oidcConfiguration" />
            </button>
          </h3>
          <div
            className="accordion-collapse collapse p-3"
            aria-labelledby="headingOne"
            data-bs-parent="#oidc-accordion"
            id="default-oidc-rights-accordion-collapse"
          >
            <Controller
              name="oidcConfiguration.defaultOIDCUserRights"
              control={control}
              render={({ field }) => {
                return (
                  <RightSelector
                    defaultValue={field?.value}
                    tenantLevelFilter="Admin"
                    onChange={(v) => {
                      field?.onChange(v);
                    }}
                  />
                );
              }}
            />
          </div>
        </div>
      </div>
    </>
  );
}
