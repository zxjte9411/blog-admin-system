import { createClient } from '@supabase/supabase-js';
import { InjectionToken } from '@angular/core';

export interface SupabaseAuthClient {
  signInWithOAuth(options: {
    provider: 'google';
    options?: { redirectTo?: string };
  }): Promise<{ error: unknown | null }>;
  getSession(): Promise<{
    data: { session: { access_token: string } | null };
    error: unknown | null;
  }>;
  signOut(options: { scope: 'local' }): Promise<{ error: unknown | null }>;
}

export interface RuntimeConfig {
  supabaseUrl?: string;
  supabasePublishableKey?: string;
}

interface LoadedRuntimeConfig {
  supabaseUrl: string;
  supabasePublishableKey: string;
}

const runtimeConfig = () =>
  (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: RuntimeConfig })
    .__BLOG_ADMIN_CONFIG__;

export const loadRuntimeConfig = (): LoadedRuntimeConfig | undefined => {
  const config = runtimeConfig();
  const supabaseUrl = config?.supabaseUrl?.trim();
  const supabasePublishableKey = config?.supabasePublishableKey?.trim();
  return supabaseUrl && supabasePublishableKey
    ? { supabaseUrl, supabasePublishableKey }
    : undefined;
};

export const isSupabaseConfigured = () => {
  return Boolean(loadRuntimeConfig());
};

export const SUPABASE_AUTH = new InjectionToken<SupabaseAuthClient>('SUPABASE_AUTH', {
  providedIn: 'root',
  factory: () => {
    let auth: ReturnType<typeof createClient>['auth'];
    const client = () => {
      if (auth) return auth;
      const config = loadRuntimeConfig();
      if (!config) {
        throw new Error('Supabase runtime configuration is missing');
      }
      auth = createClient(config.supabaseUrl, config.supabasePublishableKey).auth;
      return auth;
    };

    return {
      signInWithOAuth: (options) => client().signInWithOAuth(options),
      getSession: () => client().getSession(),
      signOut: (options) => client().signOut(options),
    };
  },
});
