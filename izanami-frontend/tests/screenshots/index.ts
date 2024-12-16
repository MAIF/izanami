import { generate as apiKeyGenerate } from "./api-keys";
import { generate as base64Wasm } from "./base64-wasm";
import { generate as bulkGenerate } from "./bulk-modification";
import { generate as contextGenerate } from "./contexts-env";
import { generate as gettingStarted } from "./gettingstarted";
import { generate as importGenerate } from "./import-v1";
import { generate as mailerGenerate } from "./mailer-configuration";
import { generate as multiConditionFeatureGenerate } from "./multi-condition-features";
import { generate as queryBuilderGenerate } from "./query-builder";
import { generate as remoteWasmoGenerate } from "./remote-wasmo";
import { generate as userInvitationGenerate } from "./user-invitation";
import { generate as webhookGenerate } from "./webhooks";
import { generate as exportImportV2 } from "./export-import-v2";
import { generate as nonBooleanFeatureGenerate } from "./non-boolean-features";
import { generate as auditLogGenerate } from "./audit-logs";

async function generateAll() {
  await apiKeyGenerate();
  await bulkGenerate();
  await contextGenerate();
  await gettingStarted();
  await importGenerate();
  await mailerGenerate();
  await multiConditionFeatureGenerate();
  await queryBuilderGenerate();
  await userInvitationGenerate();
  await base64Wasm();
  await remoteWasmoGenerate();
  await webhookGenerate();
  await exportImportV2();
  await nonBooleanFeatureGenerate();
  await auditLogGenerate();
}

generateAll();
