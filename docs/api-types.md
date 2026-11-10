# API types: keeping the web app in step with the backend

Architecture review #32 (D9). The web app's API types in `web/src/api.ts` are hand-written. The compiler now checks them against the backend's own description of the API, so a renamed or retyped field fails the build instead of showing up as a blank screen.

## How it fits together

1. **springdoc** describes the backend's endpoints and records as OpenAPI (`backend/app`, `OpenApiConfig`). The running app doesn't serve it (`springdoc.api-docs.enabled: false`).
2. **`OpenApiSpecTest`** turns it on in a test, reads it as the admin and compares it with `web/openapi.json`. It fails when they differ. The lender and insurer endpoints are switched on for this test, so their types are checked too.
3. **openapi-typescript** (7.13.0, run with `npx`, not installed: it needs TypeScript 5 and the web app uses 7) turns `openapi.json` into `web/src/api.gen.ts`.
4. **`web/src/api.check.ts`** checks each hand-written type against its generated schema. `tsc` (so `npm run build`) fails and names the field when:
   - the backend no longer sends a field the web type has, unless the web field is optional (`?:`);
   - the backend sends it as another type;
   - the backend has an enum value the web type doesn't list.

   Fields only the backend sends are fine. Null is ignored on both sides: Java has no nullability annotations here, so `api.ts` stays the one place that says which fields can be null. A Java `String` may be narrowed to the values the web expects ('PSA' | 'BGS'). A `Map<String, Object>` isn't checked inside, because the backend doesn't say what's in it.

Schema names are the Java class names with their enclosing classes (`ListingViewsOfferView`), since several controllers have their own `CardView`.

## When you change an API record

```bash
cd backend; .\gradlew.bat :app:test --tests "*OpenApiSpecTest" -Dopenapi.update=true
cd web; npm.cmd run api:types
```

Then fix what `npm.cmd run build` reports in `api.ts`. A new web type gets a line in `api.check.ts`.

CI checks both steps: the backend job fails if `openapi.json` is stale, and the web job regenerates `api.gen.ts` and fails if it differs from the committed file.
