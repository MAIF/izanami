// Generated with npx -p typescript tsc index.js --declaration --allowJs --emitDeclarationOnly --outDir .

declare class IzanamiFeatureClient {
    constructor(config: any);
    config: any;
    checkFeature(key: any, context?: {}): any;
    features(pattern?: string, context?: {}): any;
}
declare class IzanamiConfigClient {
    constructor(conf: any);
    conf: any;
    config(key: any): any;
    configs(pattern?: string): any;
}
declare class IzanamiExperimentsClient {
    constructor(conf: any);
    conf: any;
    experiment(key: any): any;
    experiments(pattern: string, clientId: any): any;
    variantFor(key: any, clientId: any): any;
    displayed(key: any, clientId: any): any;
    won(key: any, clientId: any): any;
}
export namespace Env {
    const PROD: string;
    const DEV: string;
}
export namespace defaultConfig {
    const timeout: number;
    const clientIdName: string;
    const clientSecretName: string;
    const fallbackConfig: {};
    const clientId: string;
    const clientSecret: string;
    const env: string;
}
export function featureClient(config: any): IzanamiFeatureClient;
export function configClient(config: any): IzanamiConfigClient;
export function experimentsClient(config: any): IzanamiExperimentsClient;
export function expressProxy(config: any): void;
export {};
