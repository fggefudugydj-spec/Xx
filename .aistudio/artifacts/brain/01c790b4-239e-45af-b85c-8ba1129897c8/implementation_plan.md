# LeviLab Launcher — Re-Architecture & Target 1.26.70.24 Fix Plan

An end-to-end overhaul of **LeviLab Launcher** for Android 13 (ARM64) that fixes version/edition misdetection (`1.26.62.1` vs `1.26.70.24`), hardens `Split APK` and `libminecraftpe.so` ELF64 verification, eliminates background-thread UI/Intent launch bugs, and delivers a polished bilingual Arabic/English launcher suite.

## User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Decisions from Clarification**:
> - **Strict Version & Edition Guard**: Hard-block launch buttons and display a clear mismatch warning whenever the installed package is not `com.mojang.minecraftpe.preview` version `1.26.70.24` (ARM64).
> - **Target Package Identity**: Prioritize official `com.mojang.minecraftpe.preview` while inspecting candidate Mojang packages via `versionName`, `versionCode`, and binary manifest data rather than app labels.
> - **Balanced Scope**: Upgrade launcher stability, pre-flight verification, native library/cache diagnostics, world backup/protection, graphics tuning, file management, and bilingual Material 3 UI together.

> [!WARNING]
> **Android 13 Security & Cloud Verification Boundaries**:
> - **Official Activity Launch Only**: Android 13 isolates cross-app code loading; LeviLab will never attempt unsupported in-process `dlopen` or foreign `ClassLoader` execution of Minecraft's libraries. All pre-flight checks (AXML, DEX, ELF64, `DT_NEEDED`) run safely in read-only mode, and the installed APK itself is launched via Android's official `ACTION_MAIN` / `CATEGORY_LAUNCHER` Activity Intent.
> - **Environment Verification Scope**: The cloud build environment compiles the APK and executes full JVM + Robolectric unit/UI test suites, but has no live Android emulator or ADB access. Live end-to-end launch into Minecraft Preview's main menu and world gameplay must be verified on the target Android 13 ARM64 device where `com.mojang.minecraftpe.preview` `1.26.70.24` is installed.

---

### 1. Overview & Core Concept

- **What It Does**: LeviLab acts as a pre-flight verification hub, diagnostic inspector, and companion manager for **Minecraft Bedrock Preview 1.26.70.24** on Android 13 ARM64. Before launching the installed game APK, it verifies package identity, `versionName`/`versionCode`, `arm64-v8a` ABI compatibility, `libminecraftpe.so` ELF64 integrity, and `DT_NEEDED` dependency order, then hands off cleanly to the installed game Activity.
- **Target Audience**: Android 13 ARM64 Minecraft Bedrock Preview players and modders who need deterministic version gating, safe library cache handling for Base/Split APKs, and reliable world/graphics utilities.
- **Key Value**: Eliminates false-positive version detection (`1.26.62.1`), prevents launching the wrong Minecraft edition (Release/Beta/Trial), repairs corrupted or stale native library caches, and protects user worlds with SHA-256 verified backups and overwrite guards.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Launch & Pre-Flight Health Flow**: On startup, LeviLab scans installed Mojang packages, ranks exact `Preview 1.26.70.24 ARM64` matches first, runs a pre-flight checklist (Edition, Version, `versionCode`, ARM64 ABI, `libminecraftpe.so` ELF64, Split APK integrity, Android 13 Intent readiness), and enables the primary Launch button only when all target conditions pass. Mismatched installs (e.g., `1.26.62.1` or Release edition) trigger a prominent error banner and keep launch controls locked.
  2. **Staged Official Launch Flow**: Pressing Launch opens the Launch Console screen, which runs ordered verification stages (A through E) in the background while safely dispatching stage updates and `startActivity` on the Android Main thread, recording every outcome in persistent Launch History.
  3. **Probe & Native Library Cache Flow**: Users can inspect Base/Split APK paths, binary AXML manifest permissions/activities, DEX inheritance (`MainActivity` / `GameActivity`) and `ACC_NATIVE` methods, ELF64 `DT_NEEDED` graphs, and repair/rebuild or clear the version-scoped Library Cache.
  4. **Tools, World Protection & File Manager Flow**: Users manage `options.txt` graphics presets via Storage Access Framework (SAF), inspect `level.dat` NBT metadata, export SHA-256 verified `.mcworld` archives, restore `.mcworld` backups behind an overwrite-confirmation guard, browse internal cache/SAF folders, and ping Bedrock servers over UDP `19132`.
  5. **Diagnostics & Logs Flow**: Users switch between Launch History and persistent Live Logs, filter errors, copy logs, or share a comprehensive diagnostic report.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Technical Obsidian Lab — high-contrast dark engineering console with crisp status badges and clear safety/error signaling.
  - *Color Palette & Mood*: Deep Obsidian background, Slate layered surfaces, Mint Green primary for verified `1.26.70.24` readiness, Cyan secondary for telemetry/ABI details, Amber tertiary for warnings, and Coral Red for blocked mismatches and errors, all referenced through `MaterialTheme.colorScheme`.
  - *Typography & Hierarchy*: Bilingual pairing using bundled local fonts — Cairo for Arabic (RTL), Plus Jakarta Sans for English (LTR), and JetBrains Mono for package IDs, SHA-256 digests, ELF64 headers, and log streams.
  - *Component Styling & Layout*: Edge-to-edge `Scaffold` with `WindowInsets.safeDrawing`, adaptive `NavigationBar` (phones) / `NavigationRail` (tablets/foldables >= 600dp), pill status chips, and 48dp+ interactive touch targets.

- **Interactive Feedback & Motion**: Indeterminate progress indicators during APK/ELF scanning and cache repair, live stage progression icons (Pending -> Running -> Passed/Failed), and confirmation dialogs before destructive world overwrites.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Enforce `hasMainNativeLib` + ELF64 Header Check During Discovery**
  - *Chosen Approach*: Require `quickScanApkNativeSupport` to verify the 20-byte ELF64 AArch64 header (`0x7F ELF`, `EI_CLASS=2`, `e_machine=0xB7`) and `> 1 MiB` size for `libminecraftpe.so` directly inside Base/Split APKs or `nativeLibraryDir`, and include `hasMainNativeLib` in `isExactTargetReady`.
  - *Why*: Previously, another `.so` file under `lib/arm64-v8a/` could mark a package ARM64-ready even if `libminecraftpe.so` was missing or corrupted.
  - *Alternatives Considered*: Deferring ELF checks until launch time; rejected because pre-flight readiness cards would mislead the user before tapping Launch.

- **Decision 2: Main-Thread Safe State Updates & Activity-Context Intent Dispatch**
  - *Chosen Approach*: Marshall all Compose stage callbacks (`onStageUpdate`) and `startActivity` calls onto `Dispatchers.Main`, and pass the active `Activity` context (instead of `ApplicationContext`) for direct system launches, server deep links, and pack/world Intent handoffs.
  - *Why*: Calling Compose state setters or `startActivity` from `Dispatchers.IO` or `ApplicationContext` risks snapshot race exceptions and Android 13 task-stack/ActivityOptions failures.
  - *Alternatives Considered*: Keeping `ApplicationContext` with `FLAG_ACTIVITY_NEW_TASK`; rejected as less reliable across OEM Android 13 builds.

- **Decision 3: Multi-Signal Package & Version Validation**
  - *Chosen Approach*: Cross-check `PackageManager` metadata (`packageName`, `versionName`, `longVersionCode`) against binary `AndroidManifest.xml` (AXML) when available, prioritize exact `com.mojang.minecraftpe.preview` + `1.26.70.24` matches, and explicitly surface side-by-side expected vs detected version diagnostics.
  - *Why*: Eliminates confusion between Release (`1.26.62.1`), Beta, Trial, and Preview (`1.26.70.24`) packages without relying on localized app labels.
  - *Alternatives Considered*: Using `QUERY_ALL_PACKAGES`; rejected to remain compliant with Google Play policy while using targeted `<queries>` entries.

- **Decision 4: Self-Healing Versioned Library Cache**
  - *Chosen Approach*: Key cache directories by `packageName / versionName_versionCode_lastUpdateTime / abi`, automatically prune stale directories (such as old `1.26.62.1` caches) and `.tmp` remnants, verify CRC32 + ELF64 AArch64 integrity, and pass the resolved ABI into summary builders without re-scanning APK ZIPs.
  - *Why*: Prevents stale or mismatched native library caches from causing false diagnostics or wasted storage.

---

### 4. Technical Architecture & Data Strategy

- **Architecture & Component Diagram**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Jetpack Compose UI Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  │
│  │ LaunchHealth │  │ ProbeScreen  │  │ ToolsScreen  │  │ LogsScreen  │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘  │
└─────────┼─────────────────┼─────────────────┼─────────────────┼─────────┘
          │                 │                 │                 │
          ▼                 ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  LeviViewModel (StateFlow + Coroutines)                 │
│       Preferences (DataStore)  ◄──►  LeviLogger (Persistent File)       │
└───────────────┬───────────────────────────────┬─────────────────────────┘
                │                               │
                ▼                               ▼
┌───────────────────────────────┐  ┌──────────────────────────────────────┐
│    Verification & Probe Core  │  │     Companion Tools & Launch Core    │
│ ┌───────────────────────────┐ │  │ ┌──────────────────────────────────┐ │
│ │ PackageDiscovery + AXML   │ │  │ │ InProcessLaunchCoordinator       │ │
│ │ (1.26.70.24 / Preview /   │ │  │ │ (Ordered Stages A-E, Main-Thread │ │
│ │  versionCode / ARM64)     │ │  │ │  Official Activity Intent)       │ │
│ └─────────────┬─────────────┘ │  │ └──────────────────────────────────┘ │
│               ▼               │  │ ┌──────────────────────────────────┐ │
│ ┌───────────────────────────┐ │  │ │ WorldBackup + NBT + Protection   │ │
│ │ NativeLibraryManager +    │ │  │ │ OptionsTxt + FileMgr + UDP Ping  │ │
│ │ ElfValidator + Elf64Parser│ │  │ └──────────────────────────────────┘ │
│ │ (Split APK / CRC32 Cache) │ │  └──────────────────────────────────────┘
│ └───────────────────────────┘ │
└───────────────────────────────┘
```

- **Data Model & State**:
  - `InstalledMinecraftPackage`: Captures `packageName`, `versionName`, `versionCode`, `edition` (`PREVIEW`, `RELEASE`, `BETA`, `TRIAL`), `sourceDir`, `splitSourceDirs`, `nativeLibraryDir`, `apkAbis`, `hasArm64Support`, `hasMainNativeLib`, `signingCertSha256`, and `targetMismatchReason`; `isExactTargetReady` enforces all five target conditions (`PREVIEW`, `1.26.70.24`, valid `versionCode`, `hasArm64Support`, `hasMainNativeLib`).
  - `ProbeReport` & `LibraryCacheSummary`: Tracks Base/Split APK inspection, binary AXML manifest summary, DEX `MainActivity`/`GameActivity` chain, `libminecraftpe.so` ELF64 `DT_NEEDED` topological order, and CRC32-verified cache metrics.
  - `LeviUiState`: Single immutable state stream uniting bilingual `AppLanguage` (AR/EN), launch readiness, probe diagnostics, graphics `options.txt`, world backup/protection state, SAF/internal file browser entries, UDP server ping results, and persistent logs/history.

- **Interactive Component & State Mapping**:
  - **Package Rescan & Selection**: Triggers background discovery -> updates `installedPackages` & `selectedPackage` -> runs read-only probe & cache verification -> updates Readiness Checklist and Launch Guard state.
  - **Launch Actions (Verified / Quick Official / Adjacent Split / Direct System)**: Validates `isExactTargetReady` -> transitions Launch Console stages A->E on `Dispatchers.Main` -> dispatches official Android Activity Intent from `Activity` context -> logs result to `LaunchHistory` and `LeviLogger`.
  - **Cache Repair / Clear**: Purges stale or corrupt `mc_libs` directories -> re-extracts & validates `arm64-v8a` `.so` headers/CRCs -> refreshes probe and file browser listings.
  - **World Backup / Restore & File / Options / Ping Tools**: All SAF tree/document operations, NBT parsing, SHA-256 verification, overwrite confirmation dialogs, `options.txt` serialization, and UDP RakNet pings run on `Dispatchers.IO` with explicit loading and error states.
