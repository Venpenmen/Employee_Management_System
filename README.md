# EMS v1.2.1 develop integration source

This branch contains v1.2.1 source code. It is NOT a ready-to-run Windows release. The main branch remains the v1.2.0 release.

## Build and tests
- Java 17, Maven, MySQL 8; Flyway schema V1/V2/V3.
- Run: mvn -f backend/pom.xml test
- Build: mvn -f backend/pom.xml package
- RC2 scripts are in server-complete-rc2/ and require matching app/ems-1.2.1-complete-rc2.jar, app/build-manifest-complete-rc2.json, runtime and verified release layout. These binaries are NOT included in this source tree.
- The legacy server/ directory still includes v1.2.0 settings and must NOT be used as the RC2 launcher.

## RC2 candidate
- Local HTTP entry defaults to loopback port 8483 and requires one-time local entry authorization.
- Secure mode and compatibility mode have different trust boundaries. Compatibility mode is for synthetic or sanitized data only.
- Do not overwrite a running v1.2.0 installation or copy live database files.
- See docs/RC2_DEPLOYMENT.md, docs/UPGRADE_TO_v1.2.1.md, docs/SECURITY_OPERATIONS.md and RELEASE_NOTES_v1.2.1.md.

## Validation status
The frontend and backend contributions are integrated. Compilation and automated tests do not prove full Windows installation, actual database migration, external HTTPS/FRP, or second-device behavior. See RELEASE_VALIDATION_v1.2.1.md.

Exclude data, runtime, logs, private keys, credentials, backup files and local evidence from source distribution.
