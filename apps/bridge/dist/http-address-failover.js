export async function withAddressFailover(addresses, requestAddress) {
    if (!addresses.length) throw new Error("HTTP target has no resolved address");
    let lastError;
    for (const address of addresses) {
        try { return await requestAddress(address); }
        catch (error) { lastError = error; }
    }
    throw lastError ?? new Error("HTTP target addresses are unreachable");
}
