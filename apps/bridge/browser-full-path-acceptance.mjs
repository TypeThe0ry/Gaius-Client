/*
 * Strict dual-profile browser -> RelayNode -> vanilla-server acceptance gate.
 *
 * Keep this runner deliberately boring: each profile is a separate child
 * process, the second starts only after the first exits, and this process owns
 * an independent fail-closed validation of the child's structured evidence.
 * browser-full-path-smoke.mjs owns protocol/lifecycle assertions; this file
 * owns profile ordering, environment isolation, timeout, and aggregation.
 */

import {CANONICAL_PROFILE, LEGACY_PROFILE} from "../../port/scripts/verification-profiles.mjs";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const smokePath = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const profiles = [CANONICAL_PROFILE.id, LEGACY_PROFILE.id];
const requiredAcceptance = Object.freeze({
    clients: 4,
    minimumChunkPackets: 9,
    soakMillis: 15000,
    reconnectWaves: 1,
});
const multiplayerPerformanceTarget = Object.freeze({
    maxConnectToMinimumChunksMillis: 15000,
    maxConfigurationToPlayLoginMillis: 10000,
    maxPlayLoginToFirstChunkMillis: 10000,
    maxPreMinimumChunkPacketGapMillis: 500,
    maxPlayTickGapMillis: 250,
    maxPollGapMillis: 500,
    maxParserBufferedBytes: 4 * 1024 * 1024,
    maxBrowserQueuedFrames: 1024,
    maxBrowserInboundQueuedBytes: 24 * 1024 * 1024,
    maxSoakPhaseStallMillis: 500,
});
const canonicalProfiles = Object.freeze({
    [CANONICAL_PROFILE.id]: Object.freeze({
        canonicalPath: "port/versions/" + CANONICAL_PROFILE.id + ".json",
        protocolVersion: 776,
        worldVersion: 4903,
        javaVersion: 25,
        serverSha1: "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    }),
    [LEGACY_PROFILE.id]: Object.freeze({
        canonicalPath: "port/versions/" + LEGACY_PROFILE.id + ".json",
        protocolVersion: 774,
        worldVersion: 4671,
        javaVersion: 21,
        serverSha1: "64bb6d763bed0a9f1d632ec347938594144943ed",
    }),
});
const relayRuntimeGauges = Object.freeze([
    "activeLocalTunnelSessions",
    "pendingSyntheticPlayTicks",
    "activeServerFrameDrainHandles",
    "activeServerFrameDrainTimers",
    "activeClientStallTimers",
]);
const relayRuntimeConnectionGauges = Object.freeze([
    "activeTunnelLeases",
    "activeTransportWebSockets",
]);
const browserCleanupGauges = Object.freeze([
    "activeHighWatermarks",
    "decodedSliceBacklog",
    "decoderCumulationBytes",
    "decodedPacketQueue",
]);
const runtimeJavaPolicy = Object.freeze({
    [LEGACY_PROFILE.id]: "major-exactly-21",
    [CANONICAL_PROFILE.id]: "major-at-least-25",
});
const requiredChildAcceptance = Object.freeze({
    ...requiredAcceptance,
    profiles: [...profiles],
    relayRuntimeGaugesZero: [...relayRuntimeGauges],
    relayRuntimeConnectionGauges: [...relayRuntimeConnectionGauges],
    relayDrainMaxDurationMillis: 16.7,
    relayDrainSendErrors: 0,
    relayDrainCleanupRequired: true,
    browserCleanupGaugesZero: [...browserCleanupGauges],
    syntheticMarkerLabel: "synthetic-inbound-marker",
    runtimeJavaPolicy,
    multiplayerPerformance: { ...multiplayerPerformanceTarget },
});
const strictParameters = Object.freeze({
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
});
// The child smoke has a 180s server-start wait plus 90s initial/reconnect
// waits.  Keep this process-level deadline above that worst case instead of
// racing the smoke's own bounded diagnostics.
const PROFILE_TIMEOUT_MS = 600000;
const TREE_TERM_GRACE_MS = 5000;
const TREE_KILL_COMMAND_TIMEOUT_MS = 10000;
const TREE_POST_KILL_GRACE_MS = 5000;
const TREE_GROUP_CONFIRM_TIMEOUT_MS = 5000;
const TREE_GROUP_CONFIRM_INTERVAL_MS = 50;
const PRESERVED_GAIUS_ENV_NAMES = Object.freeze([
    "GAIUS_JAVA",
    "GAIUS_JAVA_HOME",
    "GAIUS_JAVA_21",
    "GAIUS_JAVA_25",
]);
const PRESERVED_GAIUS_ENV_SET = new Set(PRESERVED_GAIUS_ENV_NAMES);
const ENV_POLLUTION_PROBE_NAMES = Object.freeze([
    "GAIUS_MAXIMUM_CONNECTIONS",
    "GAIUS_MAXIMUM_FRAME_BYTES",
    "GAIUS_FRAME_MAX_BYTES",
    "GAIUS_RELAY_URL",
    "GAIUS_RELAY_REGISTRY",
    "GAIUS_REGISTRY_URL",
    "GAIUS_REGISTRY_TOKEN",
    "GAIUS_DNS_TEST_MODE",
    "GAIUS_DNS_RETRY_LIMIT",
    "GAIUS_DNS_RETRY_BACKOFF_MS",
    "GAIUS_TRACE_TUNNEL",
    "GAIUS_ALLOW_PRIVATE_TARGETS",
    "GAIUS_TARGET_AFFINITY",
    "GAIUS_BROWSER_FULL_PATH_SERVER_JAR",
    "GAIUS_SMOKE_SERVER_JAR",
]);

const invalidInheritedParameter = Object.entries(strictParameters).find(([name, expected]) =>
    process.env[name] !== undefined && process.env[name] !== expected);
const contractEnvironmentProbe = process.argv.length === 3 &&
    process.argv[2] === "--contract-env";

if (contractEnvironmentProbe) {
    const environment = childEnvironment(CANONICAL_PROFILE.id);
    console.log(JSON.stringify({
        removedPollution: ENV_POLLUTION_PROBE_NAMES.filter((name) =>
            environment[name] === undefined),
        remainingPollution: ENV_POLLUTION_PROBE_NAMES.filter((name) =>
            environment[name] !== undefined),
        preservedJava: PRESERVED_GAIUS_ENV_NAMES.filter((name) =>
            environment[name] !== undefined),
        fixed: {
            acceptance: environment.GAIUS_BROWSER_FULL_PATH_ACCEPTANCE,
            clients: environment.GAIUS_BROWSER_FULL_PATH_CLIENTS,
            minChunks: environment.GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS,
            soakMillis: environment.GAIUS_BROWSER_FULL_PATH_SOAK_MS,
            reconnectWaves: environment.GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES,
            origin: environment.GAIUS_BROWSER_FULL_PATH_ORIGIN,
            allowedHosts: environment.GAIUS_ALLOWED_HOSTS,
        },
    }));
}
else if (process.argv.length !== 2) {
    console.error("browser-full-path-acceptance.mjs takes no arguments");
    process.exitCode = 2;
}
else if (invalidInheritedParameter !== undefined) {
    console.error(`${invalidInheritedParameter[0]} must be exactly ${invalidInheritedParameter[1]} for acceptance`);
    process.exitCode = 2;
}
else {
    const results = [];
    for (const profile of profiles) {
        // Deliberately await each child: the two profile servers must never
        // overlap and a failed first profile must not hide the second result.
        results.push(await runProfile(profile));
    }
    const actual = results.map((entry) => entry.actual ?? null);
    const observed = results.map((entry) => entry.observed ?? null);
    const actualProfileOrder = actual.map((entry) => entry?.profile?.id ?? null);
    const observedProfileOrder = observed.map((entry) => entry?.profile?.id ?? null);
    const profileOrderMatches = stableSerialize(actualProfileOrder) ===
        stableSerialize(profiles) &&
        stableSerialize(observedProfileOrder) === stableSerialize(profiles);
    const provenanceComplete = results.every((entry) =>
        entry.validation?.validatedActual === entry.actual &&
        entry.validation?.validatedObserved === entry.observed &&
        entry.actual !== null && entry.observed !== null);
    const ok = results.length === profiles.length &&
        results.every((entry) => entry.ok) && profileOrderMatches &&
        provenanceComplete;
    console.log(JSON.stringify({
        schemaVersion: "browser-full-path-acceptance-v3",
        ok,
        mode: "strict-acceptance",
        // This order is read from validated child acceptance.actual evidence,
        // not copied from the requested labels used to launch the children.
        profileOrder: actualProfileOrder,
        profileOrderMatches,
        required: {
            ...requiredAcceptance,
            profileOrder: [...profiles],
            canonicalProfiles,
            relayRuntimeGaugesZero: [...relayRuntimeGauges],
            relayRuntimeConnectionGauges: [...relayRuntimeConnectionGauges],
            browserCleanupGaugesZero: [...browserCleanupGauges],
            syntheticMarkerLabel: "synthetic-inbound-marker",
            runtimeJavaPolicy,
        },
        // Provenance is intentionally direct: actual/observed are the exact
        // acceptance sections independently validated from each child JSON.
        actual,
        observed,
        runs: results.map((entry) => ({
            requestedProfile: entry.requestedProfile,
            ok: entry.ok,
            exitCode: entry.exitCode,
            signal: entry.signal,
            timedOut: entry.timedOut,
            wallClockMillis: entry.wallClockMillis,
            timeoutMillis: entry.timeoutMillis,
            timeoutEvidence: entry.timeoutEvidence,
            validationFailures: entry.validation?.failures ?? ["validation missing"],
            error: entry.error,
            stdout: entry.stdout,
            stderr: entry.stderr,
        })),
    }));
    if (!ok) process.exitCode = 1;
}

function childEnvironment(profile) {
    const environment = { ...process.env };
    for (const name of Object.keys(environment)) {
        const upperName = name.toUpperCase();
        if (upperName.startsWith("GAIUS_") &&
            !PRESERVED_GAIUS_ENV_SET.has(upperName)) {
            delete environment[name];
        }
    }
    return {
        ...environment,
        NODE_ENV: "test",
        GAIUS_VERSION_PROFILE_PATH: `versions/${profile}.json`,
        ...strictParameters,
        // Keep the child entirely on loopback and deterministic test values.
        GAIUS_BROWSER_FULL_PATH_ORIGIN: "http://127.0.0.1:8781",
        GAIUS_BROWSER_FULL_PATH_TOKEN: "browser-full-path-token",
        GAIUS_BROWSER_FULL_PATH_USERNAME_PREFIX: "GaiusBrowser",
        GAIUS_ALLOWED_ORIGINS: "http://127.0.0.1:8781",
        GAIUS_ALLOWED_HOSTS: "127.0.0.1",
        GAIUS_BRIDGE_TOKEN: "browser-full-path-token",
        GAIUS_IDLE_TIMEOUT_MS: "60000",
        GAIUS_CONNECT_TIMEOUT_MS: "10000",
        GAIUS_PROXY_KEEPALIVES: "1",
    };
}

function runProfile(profile) {
    return new Promise((resolve) => {
        const startedAt = Date.now();
        const child = spawn(process.execPath, [smokePath, "--acceptance"], {
            cwd: bridgeDirectory,
            env: childEnvironment(profile),
            stdio: ["ignore", "pipe", "pipe"],
            shell: false,
            windowsHide: true,
            detached: process.platform !== "win32",
        });
        let stdout = "";
        let stderr = "";
        let spawnError;
        let timedOut = false;
        let timeoutAt;
        let settled = false;
        let childCloseObserved = false;
        let pendingClose;
        let treeKillInProgress = false;
        let treeKillEvidence;
        let timeoutTimer;
        let hardTimeoutTimer;
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => { stdout += chunk; });
        child.stderr.on("data", (chunk) => { stderr += chunk; });
        child.once("error", (error) => { spawnError = error; });
        const finish = (code, signal, forced = false) => {
            if (settled) return;
            if (timedOut && treeKillInProgress && !forced) {
                pendingClose = { code, signal };
                return;
            }
            settled = true;
            clearTimeout(timeoutTimer);
            clearTimeout(hardTimeoutTimer);
            const report = parseChildReport(stdout);
            const validation = validateChildResult(profile, report, {
                exitCode: code,
                signal,
                timedOut,
            });
            const ok = !forced && !timedOut && spawnError === undefined &&
                code === 0 && validation.ok;
            const timeoutTreeKill = treeKillEvidence ?? {
                strategy: process.platform === "win32"
                    ? "windows-taskkill-tree" : "unix-process-group",
                status: "not-completed",
                cleanupConfirmed: false,
            };
            const timeoutCleanupConfirmed = timeoutTreeKill.cleanupConfirmed === true ||
                (timeoutTreeKill.strategy === "windows-taskkill-tree" &&
                    timeoutTreeKill.succeeded === true && childCloseObserved);
            resolve({
                requestedProfile: profile,
                ok,
                exitCode: code,
                signal,
                timedOut,
                wallClockMillis: Date.now() - startedAt,
                timeoutMillis: PROFILE_TIMEOUT_MS,
                timeoutEvidence: timedOut ? {
                    startedAt,
                    timeoutAt,
                    childPid: child.pid ?? null,
                    childCloseObserved,
                    treeKill: {
                        ...timeoutTreeKill,
                        cleanupConfirmed: timeoutCleanupConfirmed,
                    },
                    cleanupConfirmed: timeoutCleanupConfirmed,
                } : null,
                result: report,
                validation,
                actual: validation.validatedActual,
                observed: validation.validatedObserved,
                error: spawnError === undefined ? null :
                    String(spawnError.stack || spawnError),
                stdout: ok ? "" : trimDiagnostic(stdout),
                stderr: ok ? "" : trimDiagnostic(stderr),
            });
        };
        child.once("close", (code, signal) => {
            childCloseObserved = true;
            finish(code, signal);
        });
        timeoutTimer = setTimeout(() => {
            if (settled) return;
            timedOut = true;
            timeoutAt = Date.now();
            treeKillInProgress = true;
            terminateChildProcessTree(child).then((evidence) => {
                const cleanupConfirmed = evidence.strategy === "windows-taskkill-tree"
                    ? evidence.succeeded === true && childCloseObserved
                    : evidence.cleanupConfirmed === true;
                treeKillEvidence = {
                    ...evidence,
                    childCloseObserved,
                    cleanupConfirmed,
                };
                treeKillInProgress = false;
                if (pendingClose !== undefined) {
                    const close = pendingClose;
                    pendingClose = undefined;
                    finish(close.code, close.signal);
                    return;
                }
                // Give the tree kill a bounded opportunity to deliver the
                // close event. A fallback result remains explicitly unconfirmed.
                hardTimeoutTimer = setTimeout(
                    () => finish(null, "SIGKILL", true), TREE_POST_KILL_GRACE_MS);
            }).catch((error) => {
                treeKillEvidence = {
                    strategy: process.platform === "win32"
                        ? "windows-taskkill-tree" : "unix-process-group",
                    status: "error",
                    succeeded: false,
                    error: String(error?.stack || error),
                    bounded: true,
                    childCloseObserved,
                    cleanupConfirmed: false,
                };
                treeKillInProgress = false;
                hardTimeoutTimer = setTimeout(
                    () => finish(null, "SIGKILL", true), TREE_POST_KILL_GRACE_MS);
            });
        }, PROFILE_TIMEOUT_MS);
    });
}

async function terminateChildProcessTree(child) {
    return process.platform === "win32"
        ? terminateWindowsProcessTree(child)
        : terminateUnixProcessGroup(child);
}

function signalProcessGroup(pid, signal) {
    const attempt = {
        signal,
        attempted: false,
        succeeded: false,
        error: null,
    };
    if (!Number.isInteger(pid) || pid <= 0) {
        attempt.error = "child pid unavailable";
        return attempt;
    }
    attempt.attempted = true;
    try {
        // detached=true makes the Node child the process-group leader. A
        // negative pid targets that group, including its Java/Relay children.
        process.kill(-pid, signal);
        attempt.succeeded = true;
    }
    catch (error) {
        attempt.error = String(error?.stack || error);
    }
    return attempt;
}

async function terminateUnixProcessGroup(child) {
    const term = signalProcessGroup(child.pid, "SIGTERM");
    await delay(TREE_TERM_GRACE_MS);
    const kill = signalProcessGroup(child.pid, "SIGKILL");
    const groupProbe = await confirmUnixProcessGroupGone(child.pid);
    return {
        strategy: "unix-process-group",
        detached: true,
        childPid: child.pid ?? null,
        term,
        kill,
        attempted: term.attempted || kill.attempted,
        succeeded: term.succeeded || kill.succeeded,
        errors: [term.error, kill.error].filter(Boolean),
        bounded: true,
        groupProbe,
        // Signal delivery is not cleanup proof. Only an ESRCH result from the
        // bounded process-group existence probe may set this true.
        cleanupConfirmed: groupProbe.confirmed === true,
    };
}

async function confirmUnixProcessGroupGone(pid) {
    const startedAt = Date.now();
    const deadline = startedAt + TREE_GROUP_CONFIRM_TIMEOUT_MS;
    const errors = [];
    let polls = 0;
    if (!Number.isInteger(pid) || pid <= 0) {
        return {
            attempted: false,
            confirmed: false,
            status: "invalid-pid",
            polls,
            elapsedMillis: 0,
            errors: ["child pid unavailable"],
        };
    }
    while (Date.now() <= deadline) {
        polls += 1;
        try {
            // signal 0 checks the whole detached process group without
            // changing process state. ESRCH is the only positive proof here.
            process.kill(-pid, 0);
        }
        catch (error) {
            if (error?.code === "ESRCH") {
                return {
                    attempted: true,
                    confirmed: true,
                    status: "gone",
                    polls,
                    elapsedMillis: Date.now() - startedAt,
                    errors,
                };
            }
            errors.push(String(error?.stack || error));
        }
        await delay(Math.min(TREE_GROUP_CONFIRM_INTERVAL_MS,
            Math.max(1, deadline - Date.now())));
    }
    return {
        attempted: true,
        confirmed: false,
        status: errors.length > 0 ? "unconfirmed-error" : "timed-out-alive",
        polls,
        elapsedMillis: Date.now() - startedAt,
        errors,
    };
}

function terminateWindowsProcessTree(child) {
    const childPid = child.pid ?? null;
    const command = "taskkill.exe";
    const args = childPid === null
        ? []
        : ["/PID", String(childPid), "/T", "/F"];
    if (childPid === null) {
        return Promise.resolve({
            strategy: "windows-taskkill-tree",
            command,
            args,
            attempted: false,
            succeeded: false,
            errors: ["child pid unavailable"],
            bounded: true,
            cleanupConfirmed: false,
        });
    }
    return new Promise((resolve) => {
        let stdout = "";
        let stderr = "";
        let settled = false;
        const taskkill = spawn(command, args, {
            shell: false,
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
        });
        const finish = (result) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve({
                strategy: "windows-taskkill-tree",
                command,
                args,
                ...result,
                stdout: trimDiagnostic(stdout),
                stderr: trimDiagnostic(stderr),
                bounded: true,
                cleanupConfirmed: false,
            });
        };
        const timer = setTimeout(() => {
            try { taskkill.kill(); } catch {}
            finish({
                attempted: true,
                succeeded: false,
                timedOut: true,
                errors: [`${command} timed out after ${TREE_KILL_COMMAND_TIMEOUT_MS}ms`],
            });
        }, TREE_KILL_COMMAND_TIMEOUT_MS);
        taskkill.stdout.setEncoding("utf8");
        taskkill.stderr.setEncoding("utf8");
        taskkill.stdout.on("data", (chunk) => { stdout += chunk; });
        taskkill.stderr.on("data", (chunk) => { stderr += chunk; });
        taskkill.once("error", (error) => {
            finish({
                attempted: true,
                succeeded: false,
                errors: [String(error?.stack || error)],
            });
        });
        taskkill.once("close", (code, signal) => {
            finish({
                attempted: true,
                succeeded: code === 0 && signal === null,
                exitCode: code,
                signal,
                errors: code === 0 && signal === null
                    ? [] : [`${command} exited ${code ?? "null"}${signal ? ` (${signal})` : ""}`],
            });
        });
    });
}

function parseChildReport(stdout) {
    const lines = stdout.split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
    for (let index = lines.length - 1; index >= 0; index--) {
        try {
            const value = JSON.parse(lines[index]);
            if (value && typeof value === "object" && value.schemaVersion ===
                "browser-full-path-result-v2") return value;
        }
        catch {
            // Child diagnostics are retained below; only the structured result
            // controls the process verdict.
        }
    }
    return null;
}

function validateChildResult(profile, report, processState) {
    const failures = [];
    const expected = canonicalProfiles[profile];
    const add = (condition, message) => {
        if (!condition) failures.push(message);
    };
    const equal = (actual, wanted, label) => add(actual === wanted,
        `${label}: expected ${JSON.stringify(wanted)}, observed ${JSON.stringify(actual)}`);
    const exact = (actual, wanted, label) => add(
        stableSerialize(actual) === stableSerialize(wanted),
        `${label}: expected ${stableSerialize(wanted)}, observed ${stableSerialize(actual)}`,
    );
    const record = (value) => value !== null && typeof value === "object" &&
        !Array.isArray(value);
    const array = (value) => Array.isArray(value);

    if (!record(report)) {
        failures.push("child did not emit browser-full-path-result-v2 JSON");
        return {
            ok: false,
            failures,
            validatedObserved: null,
            validatedActual: null,
        };
    }
    equal(report.schemaVersion, "browser-full-path-result-v2", "result schema");
    equal(report.ok, true, "child result ok");
    equal(processState.exitCode, 0, "child exit code");
    equal(processState.timedOut, false, "child wall-clock timeout");
    equal(processState.signal, null, "child termination signal");

    const reportProfile = report.profile;
    if (!record(reportProfile)) {
        failures.push("result.profile is missing");
    }
    else {
        equal(reportProfile.id, profile, "result profile id");
        equal(reportProfile.path, expected.canonicalPath, "result canonical profile path");
        equal(reportProfile.canonicalProfilePath, expected.canonicalPath,
            "result canonicalProfilePath");
        equal(reportProfile.protocolVersion, expected.protocolVersion,
            "result protocol version");
        equal(reportProfile.worldVersion, expected.worldVersion,
            "result world version");
        equal(reportProfile.javaVersion, expected.javaVersion,
            "result Java version");
        checkRuntimeJava(reportProfile, profile, "result profile", failures, add, equal);
        equal(reportProfile.serverSha1, expected.serverSha1,
            "result server SHA-1");
        equal(reportProfile.serverJarSha1, expected.serverSha1,
            "result serverJar SHA-1");
        equal(reportProfile.expectedServerJarSha1, expected.serverSha1,
            "result expected serverJar SHA-1");
    }

    const acceptance = report.acceptance;
    const observed = acceptance?.observed;
    const actual = acceptance?.actual;
    equal(acceptance?.ok, true, "acceptance result success");
    equal(acceptance?.mode, "strict-acceptance", "acceptance mode");
    exact(acceptance?.required, requiredChildAcceptance,
        "acceptance.required exact schema");
    equal(report.performanceContract?.mode, "strict-acceptance",
        "performance contract mode");
    equal(report.performanceContract?.strictAcceptanceTarget?.clients,
        requiredAcceptance.clients, "performance strict client target");
    equal(report.performanceContract?.strictAcceptanceTarget?.minimumChunkPackets,
        requiredAcceptance.minimumChunkPackets, "performance strict chunk target");
    equal(report.performanceContract?.strictAcceptanceTarget?.soakMillis,
        requiredAcceptance.soakMillis, "performance strict soak target");
    equal(report.performanceContract?.strictAcceptanceTarget?.reconnectWaves,
        requiredAcceptance.reconnectWaves, "performance strict wave target");
    exact(report.performanceContract?.runtimeJavaPolicy, runtimeJavaPolicy,
        "performance runtime Java policy");
    exact(report.performanceContract?.multiplayerPerformance,
        multiplayerPerformanceTarget, "performance multiplayer no-stall contract");
    equal(JSON.stringify(report.performanceContract?.relayRuntimeConnectionGauges),
        JSON.stringify([...relayRuntimeConnectionGauges]),
        "performance RelayNode connection gauge contract");
    equal(JSON.stringify(report.performanceContract?.canonicalProfiles?.[profile]),
        JSON.stringify({
            protocolVersion: expected.protocolVersion,
            worldVersion: expected.worldVersion,
            javaVersion: expected.javaVersion,
            serverSha1: expected.serverSha1,
        }), "performance canonical profile tuple");
    equal(observed?.clients, requiredAcceptance.clients, "observed client count");
    equal(observed?.minimumChunkPackets, requiredAcceptance.minimumChunkPackets,
        "observed minimum chunks");
    equal(observed?.soakMillis, requiredAcceptance.soakMillis,
        "observed requested soak");
    equal(observed?.reconnectWaveCount, requiredAcceptance.reconnectWaves,
        "observed reconnect wave count");
    add(Number.isFinite(actual?.soakMillis) &&
        actual.soakMillis >= requiredAcceptance.soakMillis,
    `actual soak elapsed less than ${requiredAcceptance.soakMillis}ms: ` +
        JSON.stringify(actual?.soakMillis));
    equal(actual?.serverJarSha1, expected.serverSha1, "actual server SHA-1");
    checkRuntimeJava(actual, profile, "actual acceptance", failures, add, equal);
    if (record(actual?.profile)) {
        equal(actual.profile.id, profile, "actual profile id");
        equal(actual.profile.path, expected.canonicalPath, "actual profile path");
        equal(actual.profile.canonicalProfilePath, expected.canonicalPath,
            "actual canonical profile path");
        equal(actual.profile.protocolVersion, expected.protocolVersion,
            "actual protocol version");
        equal(actual.profile.worldVersion, expected.worldVersion,
            "actual world version");
        equal(actual.profile.javaVersion, expected.javaVersion,
            "actual Java version");
        equal(actual.profile.serverSha1, expected.serverSha1,
            "actual profile server SHA-1");
        equal(actual.profile.expectedServerJarSha1, expected.serverSha1,
            "actual profile expected server SHA-1");
        equal(actual.profile.actualServerJarSha1, expected.serverSha1,
            "actual profile actual server SHA-1");
        checkRuntimeJava(actual.profile, profile, "actual profile", failures, add,
            equal);
    }
    else failures.push("acceptance.actual.profile is missing");
    equal(report.transport?.clients, requiredAcceptance.clients,
        "transport client count");
    equal(report.transport?.reconnectWaves, requiredAcceptance.reconnectWaves,
        "transport reconnect wave count");
    equal(report.transport?.expectedConnections,
        requiredAcceptance.clients * (requiredAcceptance.reconnectWaves + 1),
        "transport expected connections");

    if (record(observed?.profile)) {
        equal(observed.profile.id, profile, "observed profile id");
        equal(observed.profile.path, expected.canonicalPath, "observed profile path");
        equal(observed.profile.canonicalProfilePath, expected.canonicalPath,
            "observed canonical profile path");
        equal(observed.profile.protocolVersion, expected.protocolVersion,
            "observed protocol version");
        equal(observed.profile.worldVersion, expected.worldVersion,
            "observed world version");
        equal(observed.profile.javaVersion, expected.javaVersion,
            "observed Java version");
        checkRuntimeJava(observed.profile, profile, "observed profile", failures, add,
            equal);
        equal(observed.profile.serverSha1, expected.serverSha1,
            "observed server SHA-1");
        equal(observed.profile.expectedServerJarSha1, expected.serverSha1,
            "observed expected server SHA-1");
        equal(observed.profile.actualServerJarSha1, expected.serverSha1,
            "observed actual server SHA-1");
    }
    else failures.push("acceptance.observed.profile is missing");
    checkRuntimeJava(observed, profile, "observed acceptance", failures, add, equal);

    checkGaugeEvidence(observed?.finalCleanup?.relayRuntimeGauges,
        "observed final RelayNode gauges", failures, add, equal);
    checkConnectionGaugeEvidence(observed?.finalCleanup?.relayRuntimeConnectionGauges,
        "observed final RelayNode connection gauges", 0, failures, add, equal);
    checkGaugeEvidence(observed?.finalCleanup?.browserCleanupGauges,
        "observed final browser cleanup gauges", failures, add, equal,
        browserCleanupGauges);
    checkCleanup(observed?.finalCleanup, "observed final cleanup", failures, add, equal);

    checkHealth(observed?.soak, "observed soak", failures, add, equal);
    checkHealth(actual?.soak, "actual soak", failures, add, equal);
    checkSoakPerformance(observed?.soakPerformance, "observed soak performance",
        failures, add, equal);
    checkSoakPerformance(actual?.soakPerformance, "actual soak performance",
        failures, add, equal);
    checkArrayLength(observed?.reconnectWaves, requiredAcceptance.reconnectWaves,
        "observed reconnect waves", failures, add);
    if (array(observed?.reconnectWaves)) {
        for (const [index, wave] of observed.reconnectWaves.entries()) {
            equal(wave?.wave, index + 1, `observed reconnect wave ${index + 1} number`);
            equal(wave?.syntheticMarkerLabel, "synthetic-inbound-marker",
                `observed reconnect wave ${index + 1} marker`);
            checkHealth(wave?.health, `observed reconnect wave ${index + 1} health`,
                failures, add, equal);
            checkReconnectGaugeEvidence(wave?.relayRuntimeGauges,
                `observed reconnect wave ${index + 1} RelayNode gauges`,
                failures, add, equal);
            checkReconnectConnectionGaugeEvidence(wave?.relayRuntimeConnectionGauges,
                `observed reconnect wave ${index + 1} RelayNode connection gauges`,
                failures, add, equal);
        }
    }
    if (array(actual?.reconnectWaves)) {
        equal(actual.reconnectWaves.length, requiredAcceptance.reconnectWaves,
            "actual reconnect wave count");
        for (const [index, wave] of actual.reconnectWaves.entries()) {
            equal(wave?.wave, index + 1, `actual reconnect wave ${index + 1} number`);
            equal(wave?.syntheticMarkerLabel, "synthetic-inbound-marker",
                `actual reconnect wave ${index + 1} marker`);
            checkHealth(wave?.health, `actual reconnect wave ${index + 1} health`,
                failures, add, equal);
            checkReconnectGaugeEvidence(wave?.relayRuntimeGauges,
                `actual reconnect wave ${index + 1} RelayNode gauges`,
                failures, add, equal);
            checkReconnectConnectionGaugeEvidence(wave?.relayRuntimeConnectionGauges,
                `actual reconnect wave ${index + 1} RelayNode connection gauges`,
                failures, add, equal);
        }
    }
    else failures.push("acceptance.actual.reconnectWaves is missing");

    const expectedConnections = requiredAcceptance.clients *
        (requiredAcceptance.reconnectWaves + 1);
    checkArrayLength(report.clients, expectedConnections, "all result clients",
        failures, add);
    if (array(report.clients)) {
        for (const [index, client] of report.clients.entries()) {
            checkClient(client, `result client ${index}`, failures, add, equal);
        }
    }
    checkArrayLength(report.reconnectWaves, requiredAcceptance.reconnectWaves,
        "result reconnect waves", failures, add);
    if (array(report.reconnectWaves)) {
        for (const [index, wave] of report.reconnectWaves.entries()) {
            const label = `result reconnect wave ${index + 1}`;
            equal(wave?.wave, index + 1, `${label} number`);
            equal(wave?.simultaneousDrop, true, `${label} simultaneous drop`);
            equal(wave?.transportDrop?.syntheticMarkerLabel,
                "synthetic-inbound-marker", `${label} marker label`);
            equal(wave?.javaFinalClose?.cleanupAllZero, true,
                `${label} Java final-close cleanup`);
            checkArrayLength(wave?.clients, requiredAcceptance.clients,
                `${label} clients`, failures, add);
            if (array(wave?.clients)) {
                for (const [clientIndex, client] of wave.clients.entries()) {
                    checkClient(client, `${label} client ${clientIndex}`,
                        failures, add, equal);
                }
            }
            checkGaugeEvidence(wave?.relay?.runtimeGauges?.afterDrop,
                `${label} after-drop RelayNode gauges`, failures, add, equal);
            checkGaugeEvidence(wave?.relay?.runtimeGauges?.atMinimumChunks,
                `${label} at-chunks RelayNode gauges`, failures, add, equal);
            checkConnectionGaugeEvidence(wave?.relay?.runtimeConnectionGauges?.afterDrop,
                `${label} after-drop RelayNode connection gauges`, 0,
                failures, add, equal);
            checkConnectionGaugeEvidence(wave?.relay?.runtimeConnectionGauges?.atMinimumChunks,
                `${label} at-chunks RelayNode connection gauges`,
                requiredAcceptance.clients, failures, add, equal);
            checkTransportDrop(wave?.transportDrop, label, failures, add, equal);
            checkHealth(wave?.health, `${label} health`, failures, add, equal);
        }
    }

    equal(report.session?.joins, expectedConnections, "session join count");
    equal(report.session?.hasJoined, expectedConnections, "session hasJoined count");
    equal(report.transport?.webSocketConnections, expectedConnections,
        "WebSocket connection count");
    equal(report.transport?.activeChannelsAfterClose, 0,
        "browser active channels after close");
    equal(report.transport?.activeWebSocketsAfterClose, 0,
        "browser active WebSockets after close");
    checkGaugeEvidence(report.transport?.browserCleanupGaugesAfterClose,
        "transport final browser cleanup gauges", failures, add, equal,
        browserCleanupGauges);
    for (const [name, value] of [
        ["queuedBytesAfterClose", report.transport?.queuedBytesAfterClose],
        ["queuedFramesAfterClose", report.transport?.queuedFramesAfterClose],
        ["inboundQueuedBytesAfterClose", report.transport?.inboundQueuedBytesAfterClose],
        ["activeRelayTargetLeasesAfterClose",
            report.transport?.activeRelayTargetLeasesAfterClose],
    ]) {
        equal(value, 0, `transport ${name}`);
    }
    checkRelaySnapshots(report.relayRuntime, expectedConnections, failures, add, equal);
    return {
        ok: failures.length === 0,
        failures,
        // Keep the validated child sections intact. The aggregate runner uses
        // these exact objects as its provenance boundary.
        validatedObserved: observed,
        validatedActual: actual,
    };
}

function checkArrayLength(value, wanted, label, failures, add) {
    add(Array.isArray(value) && value.length === wanted,
        `${label}: expected ${wanted} entries, observed ${Array.isArray(value) ? value.length : "missing"}`);
}

function checkRuntimeJava(value, profile, label, failures, add, equal) {
    const executable = value?.runtimeJavaExecutable;
    const major = value?.runtimeJavaMajor;
    add(typeof executable === "string" && executable.trim().length > 0,
        `${label} runtime Java executable evidence is missing`);
    add(Number.isSafeInteger(major),
        `${label} runtime Java major is invalid: ${JSON.stringify(major)}`);
    const accepted = profile === LEGACY_PROFILE.id ? major === 21 : major >= 25;
    add(accepted,
        `${label} runtime Java policy failed for ${profile}: ${JSON.stringify(major)}`);
    if (profile === LEGACY_PROFILE.id && Number.isSafeInteger(major)) {
        equal(major, 21, `${label} runtime Java major (${LEGACY_PROFILE.id} exact policy)`);
    }
    if (profile === CANONICAL_PROFILE.id && Number.isSafeInteger(major)) {
        add(major >= 25,
            `${label} runtime Java major (${CANONICAL_PROFILE.id} minimum policy): ${major}`);
    }
}

function checkReconnectGaugeEvidence(evidence, label, failures, add, equal) {
    if (evidence === null || typeof evidence !== "object" || Array.isArray(evidence)) {
        failures.push(`${label}: reconnect gauge evidence is missing`);
        return;
    }
    checkGaugeEvidence(evidence.afterDrop, `${label} afterDrop`, failures, add, equal);
    checkGaugeEvidence(evidence.atMinimumChunks, `${label} atMinimumChunks`,
        failures, add, equal);
}

function checkReconnectConnectionGaugeEvidence(evidence, label, failures, add, equal) {
    if (evidence === null || typeof evidence !== "object" || Array.isArray(evidence)) {
        failures.push(`${label}: reconnect connection gauge evidence is missing`);
        return;
    }
    checkConnectionGaugeEvidence(evidence.afterDrop, `${label} afterDrop`, 0,
        failures, add, equal);
    checkConnectionGaugeEvidence(evidence.atMinimumChunks,
        `${label} atMinimumChunks`, requiredAcceptance.clients,
        failures, add, equal);
}

function checkClient(client, label, failures, add, equal) {
    if (client === null || typeof client !== "object" || Array.isArray(client)) {
        failures.push(`${label}: client evidence is missing`);
        return;
    }
    equal(client.failure, null, `${label} failure`);
    equal(client.phase, "play", `${label} phase`);
    equal(client.loginFinished, true, `${label} LOGIN liveness`);
    add(Number.isSafeInteger(client.playLoginPackets) && client.playLoginPackets > 0,
        `${label} missing PLAY login packets`);
    add(Number.isSafeInteger(client.chunkPackets) &&
        client.chunkPackets >= requiredAcceptance.minimumChunkPackets,
    `${label} received fewer than ${requiredAcceptance.minimumChunkPackets} chunks`);
    checkClientPerformance(client, label, failures, add, equal);
    const encryption = client.onlineEncryption;
    if (encryption === null || typeof encryption !== "object") {
        failures.push(`${label} online encryption evidence is missing`);
        return;
    }
    equal(encryption.required, true, `${label} online encryption required`);
    equal(encryption.encryptionRequest, true, `${label} encryption request`);
    equal(encryption.sessionJoin, true, `${label} session join`);
    equal(encryption.rsaSecretEncrypted, true, `${label} RSA secret encryption`);
    equal(encryption.rsaChallengeEncrypted, true, `${label} RSA challenge encryption`);
    equal(encryption.aesCfb8Enabled, true, `${label} AES/CFB8`);
    equal(encryption.failClosed, true, `${label} encryption fail-closed`);
    add(typeof encryption.secretFingerprint === "string" &&
        /^[0-9a-f]{64}$/u.test(encryption.secretFingerprint),
    `${label} encryption fingerprint evidence is invalid`);
}

function checkClientPerformance(client, label, failures, add, equal) {
    const timing = client?.timing;
    const performance = client?.performance;
    if (timing === null || typeof timing !== "object" ||
        performance === null || typeof performance !== "object") {
        failures.push(`${label} multiplayer performance evidence is missing`);
        return;
    }
    for (const [name, value, limit] of [
        ["connectToMinimumChunksMillis", timing.connectToMinimumChunksMillis,
            multiplayerPerformanceTarget.maxConnectToMinimumChunksMillis],
        ["configurationToPlayLoginMillis", timing.configurationToPlayLoginMillis,
            multiplayerPerformanceTarget.maxConfigurationToPlayLoginMillis],
        ["playLoginToFirstChunkMillis", timing.playLoginToFirstChunkMillis,
            multiplayerPerformanceTarget.maxPlayLoginToFirstChunkMillis],
        ["maxPreMinimumChunkPacketGapMillis",
            performance.maxPreMinimumChunkPacketGapMillis,
            multiplayerPerformanceTarget.maxPreMinimumChunkPacketGapMillis],
        ["maxPlayTickGapMillis", performance.maxPlayTickGapMillis,
            multiplayerPerformanceTarget.maxPlayTickGapMillis],
        ["maxPollGapMillis", performance.maxPollGapMillis,
            multiplayerPerformanceTarget.maxPollGapMillis],
    ]) {
        add(Number.isFinite(value) && value <= limit,
            `${label} ${name} exceeded ${limit}ms: ${JSON.stringify(value)}`);
    }
    add(Number.isSafeInteger(performance.maximumBufferedBytes) &&
        performance.maximumBufferedBytes <= multiplayerPerformanceTarget.maxParserBufferedBytes,
    `${label} parser buffer exceeded ${multiplayerPerformanceTarget.maxParserBufferedBytes}: ` +
        `${JSON.stringify(performance.maximumBufferedBytes)}`);
}

function checkSoakPerformance(performance, label, failures, add, equal) {
    if (performance === null || typeof performance !== "object") {
        failures.push(`${label} evidence is missing`);
        return;
    }
    equal(performance.ok, true, `${label} ok`);
    add(Number.isSafeInteger(performance.samples) && performance.samples >= 10,
        `${label} sample count is too low: ${JSON.stringify(performance.samples)}`);
    add(Number.isFinite(performance.maxPhaseStallMillis) &&
        performance.maxPhaseStallMillis <= multiplayerPerformanceTarget.maxSoakPhaseStallMillis,
    `${label} phase stall exceeded ${multiplayerPerformanceTarget.maxSoakPhaseStallMillis}ms: ` +
        `${JSON.stringify(performance.maxPhaseStallMillis)}`);
    add(Number.isSafeInteger(performance.maxBrowserQueuedFrames) &&
        performance.maxBrowserQueuedFrames <= multiplayerPerformanceTarget.maxBrowserQueuedFrames,
    `${label} browser queued frames exceeded limit: ` +
        `${JSON.stringify(performance.maxBrowserQueuedFrames)}`);
    add(Number.isSafeInteger(performance.maxBrowserInboundQueuedBytes) &&
        performance.maxBrowserInboundQueuedBytes <=
        multiplayerPerformanceTarget.maxBrowserInboundQueuedBytes,
    `${label} browser inbound queue exceeded limit: ` +
        `${JSON.stringify(performance.maxBrowserInboundQueuedBytes)}`);
    add(Number.isFinite(performance.maxBrowserEventLoopGapMillis) &&
        performance.maxBrowserEventLoopGapMillis <= multiplayerPerformanceTarget.maxPollGapMillis,
    `${label} browser event-loop gap exceeded limit: ` +
        `${JSON.stringify(performance.maxBrowserEventLoopGapMillis)}`);
    equal(performance.violations?.length, 0, `${label} violations`);
}

function checkHealth(health, label, failures, add, equal) {
    if (health === null || typeof health !== "object" || Array.isArray(health)) {
        failures.push(`${label}: health evidence is missing`);
        return;
    }
    equal(health.ok, true, `${label} ok`);
    checkArrayLength(health.observed?.clients, requiredAcceptance.clients,
        `${label} clients`, failures, add);
    if (Array.isArray(health.observed?.clients)) {
        for (const [index, client] of health.observed.clients.entries()) {
            checkClient(client, `${label} client ${index}`, failures, add, equal);
        }
    }
    equal(health.observed?.browser?.activeChannels, requiredAcceptance.clients,
        `${label} browser active channels`);
    equal(health.observed?.browser?.activeWebSockets, requiredAcceptance.clients,
        `${label} browser active WebSockets`);
    equal(health.observed?.relay?.activeConnections, requiredAcceptance.clients,
        `${label} Relay active connections`);
    equal(health.observed?.relay?.targetActiveConnections, requiredAcceptance.clients,
        `${label} Relay target active connections`);
    checkGaugeEvidence(health.observed?.relayRuntimeGauges,
        `${label} RelayNode gauges`, failures, add, equal);
    checkConnectionGaugeEvidence(health.observed?.relayRuntimeConnectionGauges,
        `${label} RelayNode connection gauges`, requiredAcceptance.clients,
        failures, add, equal);
}

function checkGaugeEvidence(evidence, label, failures, add, equal,
    fields = relayRuntimeGauges) {
    if (evidence === null || typeof evidence !== "object" || Array.isArray(evidence)) {
        failures.push(`${label}: gauge evidence is missing`);
        return;
    }
    equal(evidence.available, true, `${label} available`);
    equal(evidence.allZero, true, `${label} allZero`);
    if (Array.isArray(evidence.missing)) {
        equal(evidence.missing.length, 0, `${label} missing fields`);
    }
    else failures.push(`${label}: missing field list is absent`);
    equal(JSON.stringify(evidence.fields), JSON.stringify([...fields]),
        `${label} field names`);
    for (const name of fields) {
        equal(evidence.observed?.[name], 0, `${label}.${name}`);
    }
}

function checkConnectionGaugeEvidence(evidence, label, expected, failures, add, equal,
    fields = relayRuntimeConnectionGauges) {
    if (evidence === null || typeof evidence !== "object" || Array.isArray(evidence)) {
        failures.push(`${label}: connection gauge evidence is missing`);
        return;
    }
    equal(evidence.available, true, `${label} available`);
    if (Array.isArray(evidence.missing)) {
        equal(evidence.missing.length, 0, `${label} missing fields`);
    }
    else failures.push(`${label}: missing field list is absent`);
    equal(JSON.stringify(evidence.fields), JSON.stringify([...fields]),
        `${label} field names`);
    for (const name of fields) {
        equal(evidence.observed?.[name], expected, `${label}.${name}`);
    }
}

function checkTransportDrop(transportDrop, label, failures, add, equal) {
    if (transportDrop === null || typeof transportDrop !== "object") {
        failures.push(`${label}: transport drop evidence is missing`);
        return;
    }
    equal(transportDrop.abnormalWebSocketClose, true, `${label} abnormal close`);
    equal(transportDrop.syntheticMarkerLabel, "synthetic-inbound-marker",
        `${label} synthetic marker label`);
    checkArrayLength(transportDrop.evidence, requiredAcceptance.clients,
        `${label} abnormal close clients`, failures, add);
    if (Array.isArray(transportDrop.evidence)) {
        for (const [index, evidence] of transportDrop.evidence.entries()) {
            equal(evidence?.nonNormalClose, true, `${label} client ${index} non-normal close`);
            equal(evidence?.retainedEntry, true, `${label} client ${index} retained entry`);
            equal(evidence?.label, "synthetic-inbound-marker",
                `${label} client ${index} marker`);
            equal(evidence?.syntheticInboundMarker?.preserved, true,
                `${label} client ${index} marker preservation`);
            equal(evidence?.syntheticInboundMarker?.networkFrame, false,
                `${label} client ${index} marker network flag`);
        }
    }
}

function checkCleanup(cleanup, label, failures, add, equal) {
    if (cleanup === null || typeof cleanup !== "object") {
        failures.push(`${label}: cleanup evidence is missing`);
        return;
    }
    equal(cleanup.browser?.activeChannels, 0, `${label} browser active channels`);
    equal(cleanup.browser?.activeWebSockets, 0, `${label} browser active WebSockets`);
    equal(cleanup.browser?.queuedBytes, 0, `${label} browser queued bytes`);
    equal(cleanup.browser?.queuedFrames, 0, `${label} browser queued frames`);
    equal(cleanup.browser?.inboundQueuedBytes, 0,
        `${label} browser inbound queued bytes`);
    equal(cleanup.browser?.activeRelayTargetLeases, 0,
        `${label} browser active Relay target leases`);
    equal(cleanup.relay?.activeConnections, 0, `${label} Relay active connections`);
    equal(cleanup.relay?.target?.activeConnections, 0,
        `${label} Relay target active connections`);
    equal(cleanup.relay?.target?.totalConnections,
        requiredAcceptance.clients * (requiredAcceptance.reconnectWaves + 1),
        `${label} Relay target total connections`);
}

function checkRelaySnapshots(relayRuntime, expectedConnections, failures, add, equal) {
    if (relayRuntime === null || typeof relayRuntime !== "object") {
        failures.push("relayRuntime evidence is missing");
        return;
    }
    equal(relayRuntime.baseline?.activeConnections, 0,
        "Relay baseline active connections");
    equal(relayRuntime.baseline?.target, undefined,
        "Relay baseline target route");
    equal(relayRuntime.baseline?.targetEvidence?.available, false,
        "Relay baseline target evidence availability");
    checkGaugeEvidence(relayRuntime.baseline?.runtimeGaugeEvidence,
        "Relay baseline gauges", failures, add, equal);
    checkConnectionGaugeEvidence(relayRuntime.baseline?.runtimeConnectionGaugeEvidence,
        "Relay baseline connection gauges", 0, failures, add, equal);
    for (const [name, snapshot, totalConnections] of [
        ["atMinimumChunks", relayRuntime.atMinimumChunks, requiredAcceptance.clients],
        ["beforeSoak", relayRuntime.beforeSoak, expectedConnections],
        ["afterSoak", relayRuntime.afterSoak, expectedConnections],
    ]) {
        equal(snapshot?.activeConnections, requiredAcceptance.clients,
            `Relay ${name} active connections`);
        equal(snapshot?.target?.activeConnections, requiredAcceptance.clients,
            `Relay ${name} target active connections`);
        equal(snapshot?.target?.totalConnections, totalConnections,
            `Relay ${name} target total connections`);
        checkGaugeEvidence(snapshot?.runtimeGaugeEvidence,
            `Relay ${name} gauges`, failures, add, equal);
        checkConnectionGaugeEvidence(snapshot?.runtimeConnectionGaugeEvidence,
            `Relay ${name} connection gauges`, requiredAcceptance.clients,
            failures, add, equal);
    }
    checkRelayCleanup(relayRuntime.afterClose, "Relay afterClose", failures, equal);
    checkGaugeEvidence(relayRuntime.afterClose?.runtimeGaugeEvidence,
        "Relay afterClose gauges", failures, add, equal);
    checkConnectionGaugeEvidence(relayRuntime.afterClose?.runtimeConnectionGaugeEvidence,
        "Relay afterClose connection gauges", 0, failures, add, equal);
}

function checkRelayCleanup(relay, label, failures, equal) {
    equal(relay?.activeConnections, 0, `${label} active connections`);
    equal(relay?.target?.activeConnections, 0,
        `${label} target active connections`);
    equal(relay?.target?.totalConnections,
        requiredAcceptance.clients * (requiredAcceptance.reconnectWaves + 1),
        `${label} target total connections`);
}

function trimDiagnostic(value) {
    const text = String(value ?? "");
    return text.length > 12000 ? text.slice(-12000) : text;
}

function stableSerialize(value) {
    if (Array.isArray(value)) {
        return `[${value.map((entry) => stableSerialize(entry)).join(",")}]`;
    }
    if (value !== null && typeof value === "object") {
        return `{${Object.keys(value).sort().map((key) =>
            `${JSON.stringify(key)}:${stableSerialize(value[key])}`).join(",")}}`;
    }
    return JSON.stringify(value);
}
