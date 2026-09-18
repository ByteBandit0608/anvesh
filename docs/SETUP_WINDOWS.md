# Day-0 setup on Windows (no Docker yet)

## 1. JDK 17
Download **Eclipse Temurin 17 (LTS) – Windows x64 .msi**: https://adoptium.net/temurin/releases/?version=17
During install, enable **"Set JAVA_HOME variable"** and **"Add to PATH"**.
Open a NEW PowerShell and check:
```powershell
java -version      # must say 17.x
```

## 2. VS Code extensions
Install: **Extension Pack for Java** (Microsoft) and **Spring Boot Extension Pack** (VMware).

## 3. Database — Neon (free, has pgvector)
1. https://neon.tech → sign up → New project → name `anvesh`, region Singapore.
2. Copy the connection string. It looks like
   `postgresql://neondb_owner:XXXX@ep-something.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`

## 4. Run the app
In PowerShell, inside the `anvesh` folder:
```powershell
$env:DB_URL      = "jdbc:postgresql://ep-something.ap-southeast-1.aws.neon.tech/neondb?sslmode=require"
$env:DB_USER     = "neondb_owner"
$env:DB_PASSWORD = "XXXX"
.\mvnw.cmd spring-boot:run
```
(Note the `jdbc:` prefix and that user/password are separate, not inside the URL.)
First run downloads dependencies (~5 min). Success looks like:
`Started AnveshApplication in N seconds` and Flyway logging `Successfully applied 1 migration`.

## 5. Try it
Open http://localhost:8080/docs (Swagger UI). In a second PowerShell:
```powershell
python scripts\load_samples.py
python scripts\search.py "why does model accuracy drop over time"
python scripts\search.py "విద్యుత్" keyword
```

## 6. Run the tests
```powershell
.\mvnw.cmd test
```
Expect 17 passing, 3 skipped (the integration test needs Docker — Week 1).

## Troubleshooting
- `mvnw.cmd` not recognised → you are not in the `anvesh` folder (`cd` into it).
- `JAVA_HOME not found` → reinstall JDK with the JAVA_HOME option ticked, open a new terminal.
- Flyway error `extension "vector" is not available` → wrong DB; Neon supports it, local vanilla Postgres does not.
- Port 8080 in use → `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"`
