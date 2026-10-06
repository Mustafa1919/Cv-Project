/// <reference types="astro/client" />

interface ImportMetaEnv {
  readonly PUBLIC_VITRIN_API_ORIGIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare const __VITRIN_SITE_DIR__: string;
