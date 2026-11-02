# Prison Realm 23.6

Paper 26.3 plugin. Build with Java 25 and Gradle:

```bash
gradle build
```

Output: `build/libs/PrisonRealm-23.6.jar`

## Setup
1. Install the JAR in `plugins/`.
2. Start the server.
3. Stand at the desired Nether prison spawn and run `/prisonrealm setprison`.
4. Give a Prison Realm using `/prisonrealm give <player>` or craft it.
5. Place it within 15 blocks horizontally of a target. The target has 30 seconds to leave the 30x30 area. Leaving cancels capture and returns the item to the captor.
6. If the target remains, capture happens automatically and the Prison Realm block breaks.

## Release
1. The original captor places another Prison Realm block at the intended release location.
2. The captor stands beside it and runs `/summon <player>`.
3. The summoned prisoner is blindfolded, crouched/kneeling, and surrounded by four temporary pillars with chain-like particle effects.
4. The original captor stays beside that same block and runs `/unseal <player>`.

## Commands
- `/prisonrealm give <player>` (admin)
- `/prisonrealm setprison` (admin)
- `/prisonrealm status`
- `/prisonrealm sealed`
- `/prisonrealm release <player>` (admin emergency release)
- `/summon <player>`
- `/unseal <player>`

The server uses the Paper API and Java 25. Test on a backup server before production.
