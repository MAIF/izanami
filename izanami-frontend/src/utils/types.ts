import { isArray } from "lodash";
import { NotifyOnChangeProps } from "@tanstack/react-query";

export interface Option {
  value: string;
  label: string;
}

export const featureEventTypeOptions = [
  { label: "Created", value: "FEATURE_CREATED" },
  { label: "Updated", value: "FEATURE_UPDATED" },
  { label: "Deleted", value: "FEATURE_DELETED" },
] as const;

export const tenantEventTypeOptions = [
  { label: "Feature created", value: "FEATURE_CREATED" },
  { label: "Feature updated", value: "FEATURE_UPDATED" },
  { label: "Feature deleted", value: "FEATURE_DELETED" },
  { label: "Project created", value: "PROJECT_CREATED" },
  { label: "Project deleted", value: "PROJECT_DELETED" },
  { label: "Project updated", value: "PROJECT_UPDATED" },
];

export type TFeatureEventTypes =
  typeof featureEventTypeOptions[number]["value"];

export type TTenantEventTypes = typeof tenantEventTypeOptions[number]["value"];

export type LogSearchQuery = {
  users: string[];
  types: EventType[];
  start?: Date;
  end?: Date;
  order: "asc" | "desc";
  total: boolean;
  pageSize: number;
  additionalFields: { [x: string]: any };
};

export const POSSIBLE_TOKEN_RIGHTS = ["EXPORT", "IMPORT"] as const;
export type TokenTenantRight = typeof POSSIBLE_TOKEN_RIGHTS[number];

export type TokenTenantRightsArray = [string | null, TokenTenantRight[]][];
export type TokenTenantRights = { [tenant: string]: TokenTenantRight[] };

export type EventType = TTenantEventTypes;
export type LogEntry =
  | FeatureLogEntry
  | ProjectCreated
  | ProjectDeleted
  | ProjectUpdated;

interface LogEntryBase {
  eventId: number;
  type: EventType;
  user: string;
  emittedAt: Date;
  origin: "NORMAL" | "IMPORT";
  authentication: "BACKOFFICE" | "TOKEN";
  token?: string;
  tokenName?: string;
}

export interface ProjectCreated extends LogEntryBase {
  type: "PROJECT_CREATED";
  name: string;
  id: string;
  tenant: string;
}

export interface ProjectDeleted extends LogEntryBase {
  type: "PROJECT_DELETED";
  name: string;
  id: string;
  tenant: string;
}

export interface ProjectUpdated extends LogEntryBase {
  type: "PROJECT_UPDATED";
  name: string;
  id: string;
  tenant: string;
  previous: {
    name: string;
  };
}

export type FeatureLogEntry = FeatureCreated | FeatureDeleted | FeatureUpdated;

interface FeatureLogEntryBase extends LogEntryBase {
  id: string;
  project: string;
  tenant: string;
  type: TFeatureEventTypes;
}

export interface FeatureCreated extends FeatureLogEntryBase {
  type: "FEATURE_CREATED";
  conditions: FeatureEventConditions;
}

export interface FeatureUpdated extends FeatureLogEntryBase {
  type: "FEATURE_UPDATED";
  conditions: FeatureEventConditions;
  previousConditions: FeatureEventConditions;
}

type FeatureEventConditions = {
  "": TLightFeature;
  [x: string]: TLightFeature;
};

interface FeatureDeleted extends FeatureLogEntryBase {
  type: "FEATURE_DELETED";
  name: string;
}

export type PersonnalAccessToken =
  | {
      id: string;
      name: string;
      expiresAt: Date;
      expirationTimezone: string;
      createdAt: Date;
      username: string;
      allRights: boolean;
      rights: TokenTenantRights;
    }
  | {
      id: string;
      name: string;
      createdAt: Date;
      username: string;
      allRights: boolean;
      rights: TokenTenantRights;
    };

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
  name: string;
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
  admin: boolean;
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
  resultType: "boolean";
  stale?: StaleStatus;
}

export type NeverCalled = {
  because: "NeverCalled";
  since: Date;
};

export type NoCall = {
  because: "NoCall";
  since: Date;
};

export type NoValueChange = {
  because: "NoValueChange";
  since: Date;
  value: string | number | boolean;
};

export type StaleStatus = NeverCalled | NoCall | NoValueChange;

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

export type FeatureType = boolean | number | string;
export type FeatureTypeName = "number" | "string" | "boolean";

interface ClassicalFeatureBase<TypeName extends FeatureTypeName> {
  id?: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  conditions: TClassicalCondition[];
  resultType: TypeName;
  stale?: StaleStatus;
}

interface ClassicalBooleanFeature extends ClassicalFeatureBase<"boolean"> {}

interface ClassicalStringFeature extends ClassicalFeatureBase<"string"> {
  value: string;
  conditions: TValuedCondition<string>[];
}

interface ClassicalNumberFeature extends ClassicalFeatureBase<"number"> {
  value: number;
  conditions: TValuedCondition<number>[];
}

// There is probablyt a better way, but I suck a Typescript ;)
export type ClassicalFeature = ClassicalBooleanFeature | ValuedFeature;

export type ValuedFeature = ClassicalStringFeature | ClassicalNumberFeature;

export interface WasmFeature {
  id?: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  wasmConfig: TWasmConfig;
  resultType: FeatureTypeName;
}

export interface LightWasmFeature {
  id?: string;
  name: string;
  description: string;
  enabled: boolean;
  tags: string[];
  project?: string;
  wasmConfig: string;
  resultType: FeatureTypeName;
  stale?: StaleStatus;
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

export interface TClassicalCondition {
  id: string;
  rule?: TFeatureRule;
  period?: TFeaturePeriod;
}

export interface TValuedCondition<T> extends TClassicalCondition {
  value: T;
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
  level: TProjectLevel;
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
  defaultProjectRight?: TProjectLevel;
  defaultKeyRight?: TLevel;
  defaultWebhookRight?: TLevel;
}

export interface TTenantRights {
  [key: string]: TTenantRight;
}

export interface TRights {
  tenants?: TTenantRights;
}

export const TLevel = {
  Read: "Read",
  Write: "Write",
  Admin: "Admin",
} as const;

export const TProjectLevel = {
  Read: "Read",
  Update: "Update",
  Write: "Write",
  Admin: "Admin",
} as const;

export type TLevel = typeof TLevel[keyof typeof TLevel];

export type TProjectLevel = typeof TProjectLevel[keyof typeof TProjectLevel];

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
  defaultRight?: TLevel;
}

export type Mailer = "Console" | "MailJet" | "MailGun" | "SMTP";
export type InvitationMode = "Response" | "Mail";

export interface Configuration {
  version: string;
  invitationMode: InvitationMode;
  originEmail: string;
  anonymousReporting: boolean;
  anonymousReportingLastAsked: Date;
  mailerConfiguration: MailerConfiguration;
  oidcConfiguration?: OIDCSettings;
  preventOAuthModification: boolean;
}

export type TCompleteRight = TRights & { admin: boolean };
export interface RightByRoles {
  [role: string]: TCompleteRight;
}

export type MailGunRegion = "EUROPE" | "US";

export interface SMTPConfiguration {
  mailer: "SMTP";
  host: string;
  port: number;
  user?: string;
  password?: string;
  auth: boolean;
  starttlsEnabled: boolean;
  smtps: boolean;
}

export interface MailJetConfiguration {
  mailer: "MailJet";
  secret: string;
  apiKey: string;
}

export interface MailGunConfiguration {
  mailer: "MailGun";
  apiKey: string;
  region: MailGunRegion;
}

export interface ConsoleConfiguration {
  mailer: "Console";
}

export type MailerConfiguration =
  | MailJetConfiguration
  | MailGunConfiguration
  | SMTPConfiguration
  | ConsoleConfiguration
  | Record<string, never>;

export interface TContextOverloadBase<FeatureTypeName> {
  name: string;
  enabled: boolean;
  id: string;
  path: string;
  project?: string;
  conditions: TClassicalCondition[];
  resultType: FeatureTypeName;
}

export interface TBooleanContextOverload
  extends TContextOverloadBase<"boolean"> {}

export interface TNumberContextOverload extends TContextOverloadBase<"number"> {
  conditions: TValuedCondition<number>[];
  value: number;
}

export interface TStringContextOverload extends TContextOverloadBase<"string"> {
  conditions: TValuedCondition<string>[];
  value: string;
}

export type TClassicalContextOverload =
  | TBooleanContextOverload
  | TNumberContextOverload
  | TStringContextOverload;

export interface TWasmContextOverload {
  name: string;
  enabled: boolean;
  id: string;
  path: string;
  project?: string;
  wasmConfig: TWasmConfig;
  resultType: FeatureTypeName;
}

export type TContextOverload = TClassicalContextOverload | TWasmContextOverload;

export interface TContext {
  name: string;
  id: string;
  children: TContext[];
  overloads: TContextOverload[];
  global: boolean;
  project: string;
  protected: boolean;
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
  id: string;
};

export type SearchResultPathElement = {
  type: "global_context" | "local_context" | "project" | "tenant";
  name: string;
};

export interface PKCEConfig {
  enabled: boolean;
  algorithm: string;
}

export const rightRoleModes = ["Supervised", "Initial"] as const;
type TRoleRightMode = typeof rightRoleModes[number];

export interface OIDCSettings {
  method: "BASIC" | "POST";
  enabled: boolean;
  clientId: string;
  clientSecret: string;
  tokenUrl: string;
  authorizeUrl: string;
  loginUrl: string;
  scopes: string;
  pkce?: PKCEConfig;
  nameField: string;
  emailField: string;
  callbackUrl: string;
  userRightsByRoles?: RightByRoles;
  roleClaim?: string;
  roleRightMode?: TRoleRightMode;
}
