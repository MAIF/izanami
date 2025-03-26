import { GlobalContextIcon } from "../../utils/icons";

export const SearchTypeEnum = {
  TENANT: {
    value: "tenant",
    label: "Tenants",
    icon: <i className="fas fa-cloud me-2" aria-hidden="true" />,
  },
  PROJECT: {
    value: "project",
    label: "Projects",
    icon: <i className="fas fa-building me-2" aria-hidden="true" />,
  },
  FEATURE: {
    value: "feature",
    label: "Features",
    icon: <i className="fas fa-rocket me-2" aria-hidden="true" />,
  },
  KEY: {
    value: "key",
    label: "Keys",
    icon: <i className="fas fa-key me-2" aria-hidden="true" />,
  },
  GLOBAL_CONTEXT: {
    value: "global_context",
    label: "Global Contexts",
    icon: (
      <span aria-hidden="true" className="me-2">
        <GlobalContextIcon />
      </span>
    ),
  },
  LOCAL_CONTEXT: {
    value: "local_context",
    label: "Local Contexts",
    icon: <i className="fas fa-filter me-2" aria-hidden="true" />,
  },
  TAG: {
    value: "tag",
    label: "Tags",
    icon: <i className="fas fa-tag me-2" aria-hidden="true" />,
  },
  WEBHOOK: {
    value: "webhook",
    label: "WebHooks",
    icon: <i className="fas fa-plug me-2" aria-hidden="true" />,
  },
  SCRIPT: {
    value: "script",
    label: "WASM scripts",
    icon: <i className="fas fa-code me-2" aria-hidden="true" />,
  },
} as const;
