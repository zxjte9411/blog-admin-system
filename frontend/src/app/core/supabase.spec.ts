import { loadRuntimeConfig } from './supabase';

describe('Supabase runtime configuration', () => {
  afterEach(() => {
    delete (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object })
      .__BLOG_ADMIN_CONFIG__;
  });

  it('loads the complete public configuration', () => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ = {
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    };

    expect(loadRuntimeConfig()).toEqual({
      supabaseUrl: 'https://project.supabase.co',
      supabasePublishableKey: 'sb_publishable_test',
    });
  });

  it.each([
    { supabasePublishableKey: 'sb_publishable_test' },
    { supabaseUrl: 'https://project.supabase.co' },
  ])('does not load an incomplete public configuration', (config) => {
    (globalThis as typeof globalThis & { __BLOG_ADMIN_CONFIG__?: object }).__BLOG_ADMIN_CONFIG__ =
      config;

    expect(loadRuntimeConfig()).toBeUndefined();
  });
});
