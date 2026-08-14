# BlogAdmin

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 21.2.20.

## Google Login local setup

Copy `public/config.example.js` to `public/config.js`, then set the Supabase
project URL and publishable key. In Supabase Authentication URL Configuration,
add `http://localhost:4200/login` to the redirect allow list. `config.js` is
ignored by Git and must not contain a service role key or any other secret.

The backend also requires the Supabase JWT issuer and JWKS URL. For a project
URL such as `https://<project-ref>.supabase.co`, set these non-secret values in
the root `.env`:

```text
SUPABASE_JWT_ISSUER=https://<project-ref>.supabase.co/auth/v1
SUPABASE_JWKS_URL=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
```

Do not put secrets in these URLs or in `config.js`. If either frontend public
setting (project URL or publishable key) is missing, Google Login is hidden;
Email/Password login remains available.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
