import { isArray } from "lodash";

export interface TenantInCreationType {
  name: string;
  description?: string;
}

export interface TKey {
  name: string;
  clientId: string;
  clientSecret: string;
  enabled: boolean;
  description?: string;
  tags?: string[];
  projects?: string[];
  admin: boolean;
}

export interface TenantType extends TenantInCreationType {
  id: string;
  projects?: TenantProjectType[];
  tags?: TagType[];
}

export interface ProjectInCreationType {
  name: string;
  description?: string;
}

export interface TenantProjectType extends ProjectInCreationType {
  id: string;
}

export interface ProjectType extends TenantProjectType {
  features: TLightFeature[];
}

export interface UserType {
  admin : boolean;
  username: string;
}

export interface TagType {
  id: string;
  name: string;
  description: string;
}

export interface FeatureInCreation {
  enabled: boolean;
  name?: string;
  tags?: string[];
}

export type TLightFeature =
  | ClassicalFeature
  | LightWasmFeature
  | SingleConditionFeature;

export type TCompleteFeature =
  | ClassicalFeature
  | WasmFeature
  | SingleConditionFeature;

export function isClassicalFeature(feature: TLightFeature | TCompleteFeature) {
  const lightF = feature as LightWasmFeature;
  const completeF = feature as WasmFeature;
  return (
    !isLightWasmFeature(lightF) &&
    !isSingleConditionFeature(feature) &&
    !isWasmFeature(completeF)
  );
}

export function isSingleConditionFeature(
  feature: TLightFeature | TCompleteFeature
): feature is SingleConditionFeature {
  const f = feature as SingleConditionFeature;
  return f.conditions && !isArray(f.conditions);
}

export interface SingleConditionFeature {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  conditions: Record<string, any>;
}

export interface SinglePercentageConditionFeature
  extends SingleConditionFeature {
  conditions: {
    percentage: number;
  };
}

export function isSinglePercentageConditionFeature(
  feature: TLightFeature | TCompleteFeature
): feature is SinglePercentageConditionFeature {
  return (
    (feature as SinglePercentageConditionFeature)?.conditions?.percentage !==
    undefined
  );
}

export interface SingleCustomerConditionFeature extends SingleConditionFeature {
  conditions: {
    users: string[];
  };
}

export function isSingleCustomerConditionFeature(
  feature: TLightFeature | TCompleteFeature
): feature is SingleCustomerConditionFeature {
  return (
    (feature as SingleCustomerConditionFeature)?.conditions?.users !== undefined
  );
}

export interface SingleDateRangeConditionFeature
  extends SingleConditionFeature {
  conditions: {
    timezone: string;
    begin: Date;
    end: Date;
  };
}

export function isSingleDateRangeConditionFeature(
  feature: TLightFeature | TCompleteFeature
): feature is SingleDateRangeConditionFeature {
  return (
    (feature as SingleDateRangeConditionFeature)?.conditions?.begin !==
    undefined
  );
}

export interface SingleHourRangeConditionFeature
  extends SingleConditionFeature {
  conditions: {
    timezone: string;
    startTime: string;
    endTime: string;
  };
}

export function isSingleHourRangeConditionFeature(
  feature: TLightFeature | TCompleteFeature
): feature is SingleHourRangeConditionFeature {
  const f = feature as SingleHourRangeConditionFeature;
  return (
    f?.conditions?.startTime !== undefined ||
    f?.conditions?.endTime !== undefined
  );
}

export interface SingleNoStrategyConditionFeature
  extends SingleConditionFeature {
  conditions: Record<string, never>;
}

export function isSingleNoStrategyConditionFeature(
  feature: TLightFeature | TCompleteFeature
): feature is SingleNoStrategyConditionFeature {
  return (
    isSingleConditionFeature(feature) &&
    Object.keys((feature as SingleNoStrategyConditionFeature).conditions)
      ?.length === 0
  );
}

export interface ClassicalFeature {
  id?: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  conditions: TCondition[];
}

export interface WasmFeature {
  id?: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  wasmConfig: TWasmConfig;
}

export interface LightWasmFeature {
  id?: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  wasmConfig: string;
}

export interface TWasmConfig {
  name: string;
  source: {
    kind: string;
    path: string;
    opts?: { [x: string]: any };
  };
  memoryPages?: number;
  functionName?: string;
  wasi?: boolean;
  opa?: boolean;
}

export interface TWasmConfigSource {
  kind: string;
  path: string;
  opts?: { [x: string]: any };
}

export interface TCondition {
  rule?: TFeatureRule;
  period?: TFeaturePeriod;
}

export interface THourPeriod {
  startTime: string;
  endTime: string;
}

export interface TDayOfWeepPeriod {
  days: TDays[];
}

export const DAYS = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
] as const;

export type TDays = typeof DAYS[number];

export interface TFeaturePeriod {
  begin?: Date;
  end?: Date;
  hourPeriods: THourPeriod[];
  activationDays?: TDayOfWeepPeriod;
  timezone: string;
}

interface TPercentageRule {
  percentage: number;
}

export function isPercentageRule(rule?: TFeatureRule): rule is TPercentageRule {
  if (!rule) {
    return false;
  }
  return (rule as TPercentageRule).percentage !== undefined;
}

interface TUserList {
  users: string[];
}

export function isUserListRule(rule: TFeatureRule): rule is TUserList {
  return (rule as TUserList).users !== undefined;
}

export function isWasmFeature(
  feature: TCompleteFeature
): feature is WasmFeature {
  return (
    (feature as WasmFeature).wasmConfig !== undefined &&
    typeof (feature as WasmFeature).wasmConfig === "object"
  );
}

export function isLightWasmFeature(
  feature: TLightFeature
): feature is LightWasmFeature {
  return (
    (feature as LightWasmFeature).wasmConfig !== undefined &&
    typeof (feature as LightWasmFeature).wasmConfig === "string"
  );
}

export type TFeatureRule = TPercentageRule | TUserList;

export interface TProjectRight {
  level: TLevel;
}

export interface TKeyRight {
  level: TLevel;
}

export interface TWebhookRight {
  level: TLevel;
}

export interface TTenantRight {
  level: TLevel;
  projects: {
    [key: string]: TProjectRight;
  };
  keys: {
    [key: string]: TKeyRight;
  };
  webhooks: {
    [key: string]: TWebhookRight;
  };
}

export interface TTenantRights {
  [key: string]: TTenantRight;
}

export interface TRights {
  tenants?: TTenantRights;
}

export const TLevel = {
  Admin: "Admin",
  Write: "Write",
  Read: "Read",
} as const;

export type TLevel = typeof TLevel[keyof typeof TLevel];

export interface TUser {
  username: string;
  admin: boolean;
  rights: TRights;
  email: string;
  userType: "INTERNAL" | "OIDC" | "OTOROSHI";
  defaultTenant?: string;
  external?: boolean;
}

export interface TSingleRightForTenantUser {
  username: string;
  email: string;
  admin: boolean;
  userType: "INTERNAL" | "OIDC" | "OTOROSHI";
  right: TLevel;
  tenantAdmin: boolean;
}

export type Mailer = "Console" | "MailJet" | "MailGun" | "SMTP";
export type InvitationMode = "Response" | "Mail";

export interface Configuration {
  version: string;
  mailer: Mailer;
  invitationMode: InvitationMode;
  originEmail: string;
  anonymousReporting: boolean;
  anonymousReportingLastAsked: Date;
}

export interface MailJetConfigurationDetails {
  secret: string;
  apiKey: string;
}

export type MailGunRegion = "EUROPE" | "US";

export interface MailGunConfigurationDetails {
  apiKey: string;
  region: MailGunRegion;
}

export interface SMTPConfigurationDetails {
  host: string;
  port?: number;
  user?: string;
  password?: string;
  auth: boolean;
  starttlsEnabled: boolean;
  smtps: boolean;
}

export interface SMTPConfiguration {
  mailerType: "SMTP";
  configuration: SMTPConfigurationDetails;
}

export interface MailJetConfiguration {
  mailerType: "MailJet";
  configuration: MailJetConfigurationDetails;
}

export interface MailGunConfiguration {
  mailerType: "MailGun";
  configuration: MailGunConfigurationDetails;
}

export interface ConsoleConfiguration {
  mailerType: "Console";
}

export type MailerConfiguration =
  | MailJetConfigurationDetails
  | MailGunConfigurationDetails
  | SMTPConfigurationDetails
  | Record<string, never>;

export interface TContextOverload {
  name: string;
  enabled: boolean;
  id: string;
  conditions?: TCondition[];
  path?: string;
  wasmConfig?: TWasmConfig;
  project?: string;
}

export interface TContext {
  name: string;
  id: string;
  children: TContext[];
  overloads: TContextOverload[];
  global: boolean;
  project: string;
}

export interface IzanamiV1ImportRequest {
  featureFiles: FileList;
  userFiles: FileList;
  keyFiles: FileList;
  scriptFiles: FileList;
  tenant: string;
  project: string;
  newProject: boolean;
  deduceProject: boolean;
  conflictStrategy: string;
  zone: string;
  projectPartSize: number;
  inlineScript: boolean;
}

export interface LightWebhook {
  name: string;
  description: string;
  url: string;
  features: string[];
  projects: string[];
  context: string;
  user: string;
  headers: { [x: string]: string };
  enabled: boolean;
  bodyTemplate?: string;
  global: boolean;
}

export interface Webhook {
  id: string;
  name: string;
  description: string;
  url: string;
  context: string;
  user: string;
  features: {
    id: string;
    name: string;
    project: string;
  }[];
  projects: {
    name: string;
    id: string;
  }[];
  headers: { [x: string]: string };
  enabled: boolean;
  bodyTemplate?: string;
  global: boolean;
}

export type IzanamiTenantExportRequest = {
  allProjects: boolean;
  allKeys: boolean;
  allWebhooks: boolean;
  projects?: string[];
  keys?: string[];
  webhooks?: string[];
  userRights: boolean;
};

export type ImportRequest = {
  file: FileList;
  conflictStrategy: string;
};

export interface LightWebhook {
  name: string;
  description: string;
  url: string;
  features: string[];
  projects: string[];
  context: string;
  user: string;
  headers: { [x: string]: string };
  enabled: boolean;
  bodyTemplate?: string;
  global: boolean;
}

export interface Webhook {
  id: string;
  name: string;
  description: string;
  url: string;
  context: string;
  user: string;
  features: {
    id: string;
    name: string;
    project: string;
  }[];
  projects: {
    name: string;
    id: string;
  }[];
  headers: { [x: string]: string };
  enabled: boolean;
  bodyTemplate?: string;
  global: boolean;
}

export interface SearchEntityResponse {
  id: string;
  origin_table: string;
  origin_tenant: string;
  name: string;
  project: string;
  description: string;
  parent: string;
  similarity_name: number;
  similarity_description: number;
}

export type SearchResult = {
  type:
    | "feature"
    | "project"
    | "key"
    | "tag"
    | "script"
    | "global_context"
    | "local_context"
    | "webhook";
  name: string;
  path: SearchResultPathElement[];
  tenant: string;
};

export type SearchResultPathElement = {
  type: "global_context" | "local_context" | "project" | "tenant";
  name: string;
};
