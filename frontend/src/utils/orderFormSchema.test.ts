import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { orderFormSchema, type OrderFormInput } from './orderFormSchema'

/** The smallest input that passes validation; each test overrides one field. */
const valid: OrderFormInput = {
  patientFirstName: 'Jane',
  patientLastName: 'Doe',
  patientMrn: '123456',
  providerName: 'Dr. Smith',
  providerNpi: '1234567890',
  medicationName: 'IVIG',
  primaryDiagnosis: 'G70.00',
}

function parse(overrides: Partial<OrderFormInput> = {}) {
  return orderFormSchema.safeParse({ ...valid, ...overrides })
}

/** All messages reported for one field (empty array when the field is valid). */
function messagesFor(result: ReturnType<typeof parse>, field: string): string[] {
  if (result.success) return []
  return result.error.issues.filter((i) => i.path[0] === field).map((i) => i.message)
}

describe('orderFormSchema', () => {
  it('accepts the minimal valid input', () => {
    expect(parse().success).toBe(true)
  })

  it('accepts a fully populated input', () => {
    const result = parse({
      patientDateOfBirth: '1980-01-31',
      patientSex: 'Female',
      patientWeightKg: '70.5',
      patientAllergies: 'Penicillin',
      additionalDiagnosesText: 'I10\nN18.3',
      medicationHistoryText: 'Metformin',
      patientRecords: 'Notes',
    })
    expect(result.success).toBe(true)
  })

  describe('required text fields', () => {
    it.each([
      ['patientFirstName', 'First name is required'],
      ['patientLastName', 'Last name is required'],
      ['providerName', 'Provider name is required'],
      ['medicationName', 'Medication name is required'],
    ] as const)('rejects an empty %s', (field, message) => {
      expect(messagesFor(parse({ [field]: '' }), field)).toEqual([message])
    })

    it('reports every missing required field at once', () => {
      const result = orderFormSchema.safeParse({})
      const failing = new Set(result.success ? [] : result.error.issues.map((i) => i.path[0]))
      expect(failing).toEqual(
        new Set([
          'patientFirstName',
          'patientLastName',
          'patientMrn',
          'providerName',
          'providerNpi',
          'medicationName',
          'primaryDiagnosis',
        ]),
      )
    })

    // Characterization: min(1) counts a space as a character, so a name made only of
    // spaces is accepted today. Pinned so that tightening it later is a conscious change.
    it('currently accepts a whitespace-only first name', () => {
      expect(parse({ patientFirstName: '   ' }).success).toBe(true)
    })
  })

  describe('patientMrn', () => {
    it('accepts exactly 6 digits', () => {
      expect(messagesFor(parse({ patientMrn: '000123' }), 'patientMrn')).toEqual([])
    })

    it.each(['', '12345', '1234567', '12345a'])('rejects %j', (mrn) => {
      expect(messagesFor(parse({ patientMrn: mrn }), 'patientMrn')).toEqual([
        'MRN must be exactly 6 digits',
      ])
    })
  })

  describe('providerNpi', () => {
    it('accepts exactly 10 digits', () => {
      expect(messagesFor(parse({ providerNpi: '9876543210' }), 'providerNpi')).toEqual([])
    })

    it.each(['', '123456789', '12345678901', '12345abcde'])('rejects %j', (npi) => {
      expect(messagesFor(parse({ providerNpi: npi }), 'providerNpi')).toEqual([
        'NPI must be exactly 10 digits',
      ])
    })
  })

  describe('primaryDiagnosis', () => {
    it.each(['E11.9', 'E11', 'Z99.89'])('accepts %s', (code) => {
      expect(messagesFor(parse({ primaryDiagnosis: code }), 'primaryDiagnosis')).toEqual([])
    })

    it.each(['', 'U07.1', 'e11.9', 'E1.9', 'E11.'])('rejects %j', (code) => {
      expect(messagesFor(parse({ primaryDiagnosis: code }), 'primaryDiagnosis')).toEqual([
        'Must be a valid ICD-10 code (e.g. E11.9)',
      ])
    })
  })

  describe('patientDateOfBirth', () => {
    // Freeze "now" so the today / tomorrow boundary is deterministic.
    beforeEach(() => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-06-15T12:00:00Z'))
    })
    afterEach(() => {
      vi.useRealTimers()
    })

    it('is optional: undefined and empty string are accepted', () => {
      expect(parse({ patientDateOfBirth: undefined }).success).toBe(true)
      expect(parse({ patientDateOfBirth: '' }).success).toBe(true)
    })

    it('accepts a past date and today', () => {
      expect(parse({ patientDateOfBirth: '1980-01-31' }).success).toBe(true)
      expect(parse({ patientDateOfBirth: '2026-06-15' }).success).toBe(true)
    })

    it('rejects tomorrow', () => {
      expect(messagesFor(parse({ patientDateOfBirth: '2026-06-16' }), 'patientDateOfBirth')).toEqual([
        'Date of birth cannot be in the future',
      ])
    })
  })

  describe('patientSex', () => {
    it.each(['Male', 'Female', 'Other', ''])('accepts %j', (sex) => {
      expect(parse({ patientSex: sex as OrderFormInput['patientSex'] }).success).toBe(true)
    })

    it('accepts undefined (field not touched)', () => {
      expect(parse({ patientSex: undefined }).success).toBe(true)
    })

    it.each(['male', 'Unknown', 'M'])('rejects %j (case-sensitive enum)', (sex) => {
      expect(parse({ patientSex: sex as OrderFormInput['patientSex'] }).success).toBe(false)
    })
  })

  describe('patientWeightKg', () => {
    function weightResult(value: unknown) {
      return orderFormSchema.safeParse({ ...valid, patientWeightKg: value })
    }

    it('accepts undefined and the empty string (both mean "not provided")', () => {
      expect(weightResult(undefined).success).toBe(true)
      const empty = weightResult('')
      expect(empty.success).toBe(true)
      expect(empty.data?.patientWeightKg).toBe('')
    })

    it('coerces a numeric string to a number', () => {
      const result = weightResult('70.5')
      expect(result.success).toBe(true)
      expect(result.data?.patientWeightKg).toBe(70.5)
    })

    it('accepts the upper boundary 500', () => {
      expect(weightResult('500').success).toBe(true)
    })

    it.each(['500.1', '0', '-1', 'abc', '1e3'])('rejects %j', (weight) => {
      expect(weightResult(weight).success).toBe(false)
    })
  })
})
