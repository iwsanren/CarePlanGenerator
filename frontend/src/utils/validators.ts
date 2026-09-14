/** Mirrors the backend's validation rules exactly — see Icd10Codes.java, CreateOrderRequest.java. */
export const NPI_REGEX = /^\d{10}$/
export const MRN_REGEX = /^\d{6}$/
// Excludes the reserved "U" category; positions 2-3 must be digits; 1-4 word chars after the decimal.
export const ICD10_REGEX = /^[A-TV-Z]\d{2}(\.\w{1,4})?$/

export function isValidNPI(value: string): boolean {
    return NPI_REGEX.test(value)
}

export function isValidICD10(value: string): boolean {
    return ICD10_REGEX.test(value)
}

/** Splits a textarea's raw text into a trimmed, non-empty-line array. */
export function linesToList(raw: string): string[] {
    return raw
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0)
}
