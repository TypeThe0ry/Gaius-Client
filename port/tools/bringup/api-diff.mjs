const unique = (changes) => {
    const seen = new Set();
    return changes.filter((change) => {
        const key = JSON.stringify(change);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
};

/** Parse javac diagnostics into stable, actionable API deltas. */
export function parseJavaApiErrors(text) {
    const lines = String(text ?? "").split(/\r?\n/u);
    const changes = [];
    const context = (index) => lines.slice(Math.max(0, index - 3), index + 4).join("\n");
    for (let index = 0; index < lines.length; index++) {
        const line = lines[index];
        if (/cannot find symbol/u.test(line)) {
            const block = lines.slice(index, index + 5).join(" ");
            const symbol = /symbol:\s+(?:class|interface|variable|method)\s+([\w$<>.,()\[\]/?]+)/u.exec(block)?.[1];
            const owner = /location:\s+(?:class|interface|package)\s+([\w$./]+)/u.exec(block)?.[1] ?? null;
            if (symbol) {
                const nested = /symbol:\s+(?:class|interface)\s+/u.test(block) && owner && /\b(?:class|interface)\b/u.test(block);
                changes.push({category: nested ? "removed-type" : "missing-symbol", owner, symbol,
                    evidence: context(index)});
            }
            continue;
        }
        let match = /constructor\s+([\w$.]+)\s+in\s+class\s+([\w$.]+)\s+cannot be applied to given types/u.exec(line);
        if (match) {
            const required = /required:\s*(.*)/u.exec(lines[index + 1] ?? "")?.[1]?.trim() ?? null;
            const actual = /found:\s*(.*)/u.exec(lines[index + 2] ?? "")?.[1]?.trim() ?? null;
            changes.push({category: "constructor-changed", owner: match[2], symbol: match[1], expected: required,
                actual, evidence: context(index)});
            continue;
        }
        match = /method\s+([\w$]+)\(([^)]*)\)\s+in\s+([\w$.]+)\s+cannot be applied to given types/u.exec(line);
        if (match) {
            const required = /required:\s*(.*)/u.exec(lines[index + 1] ?? "")?.[1]?.trim() ?? null;
            const actual = /found:\s*(.*)/u.exec(lines[index + 2] ?? "")?.[1]?.trim() ?? null;
            changes.push({category: "method-signature-changed", owner: match[3], symbol: `${match[1]}(${match[2]})`,
                expected: required, actual, evidence: context(index)});
            continue;
        }
        match = /([\w$]+) is not abstract and does not override abstract method\s+(.+?)\s+in\s+([\w$.]+)/u.exec(line);
        if (match) {
            changes.push({category: "added-abstract-method", owner: match[3], symbol: match[2], implementor: match[1],
                evidence: context(index)});
            continue;
        }
        match = /method\s+(.+?)\s+in\s+([\w$.]+)\s+cannot be accessed/u.exec(line);
        if (match) changes.push({category: "method-access-changed", owner: match[2], symbol: match[1], evidence: context(index)});
        if (/does not override or implement a method from a supertype/u.test(line)) {
            changes.push({category: "override-removed", owner: null, symbol: null, evidence: context(index)});
        }
    }
    return unique(changes);
}

export function apiDiffReport(kind, source, log, action) {
    const changes = parseJavaApiErrors(log);
    return {kind, source, changes, errorCount: String(log ?? "").split(/\r?\n/u)
        .filter((line) => /\berror:\s/u.test(line)).length,
        actionable: changes.length > 0,
        action: action ?? "Inspect each changed owner/signature, update the adapter, and rerun the bring-up stage.",
        rawLog: source};
}
