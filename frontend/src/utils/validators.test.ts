import { describe, expect, it } from 'vitest'
import { ICD10_REGEX, MRN_REGEX, NPI_REGEX, isValidICD10, isValidNPI, linesToList } from './validators'

describe('isValidNPI', () => {
  it.each(['1234567890', '0000000000'])('accepts exactly 10 digits: %s', (value) => {
    expect(isValidNPI(value)).toBe(true)
  })

  it.each([
    ['empty', ''],
    ['9 digits', '123456789'],
    ['11 digits', '12345678901'],
    ['letters mixed in', '12345abcde'],
    ['leading space', ' 123456789'],
    ['trailing space', '123456789 '],
    ['dash', '123-456-78'],
    ['full-width digits', '１２３４５６７８９０'],
  ])('rejects %s', (_name, value) => {
    expect(isValidNPI(value)).toBe(false)
  })
})

describe('MRN_REGEX', () => {
  it('accepts exactly 6 digits, including leading zeros', () => {
    expect(MRN_REGEX.test('123456')).toBe(true)
    expect(MRN_REGEX.test('000001')).toBe(true)
  })

  it.each(['', '12345', '1234567', '12345a', ' 12345'])('rejects %j', (value) => {
    expect(MRN_REGEX.test(value)).toBe(false)
  })
})

describe('isValidICD10', () => {
  it.each([
    'E11.9', // typical
    'E11', // category only, no decimal part
    'A00.0', // lower boundary of the letter range
    'Z99.89',
    'T88.7XXA', // 4 characters after the decimal point
    'V01.0', // the letter after the reserved U
  ])('accepts %s', (code) => {
    expect(isValidICD10(code)).toBe(true)
  })

  it.each([
    ['empty', ''],
    ['reserved U category', 'U07.1'],
    ['lowercase letter', 'e11.9'],
    ['only one digit before the dot', 'E1.9'],
    ['dot with nothing after it', 'E11.'],
    ['5 characters after the dot', 'E11.12345'],
    ['leading space', ' E11.9'],
    ['trailing space', 'E11.9 '],
    ['two letters', 'EE1.9'],
    ['no letter', '111.9'],
  ])('rejects %s', (_name, code) => {
    expect(isValidICD10(code)).toBe(false)
  })

  // Characterization: the rule mirrors the backend and uses \w, which also
  // allows an underscore after the dot. Pinned here so a change is deliberate.
  it('currently accepts an underscore after the decimal point (mirrors backend \\w)', () => {
    expect(isValidICD10('E11._')).toBe(true)
  })

  it('exposes the same pattern through ICD10_REGEX and NPI_REGEX', () => {
    expect(ICD10_REGEX.test('E11.9')).toBe(true)
    expect(NPI_REGEX.test('1234567890')).toBe(true)
  })
})

describe('linesToList', () => {
  it('splits on newlines and trims every line', () => {
    expect(linesToList('  I10 \n N18.3')).toEqual(['I10', 'N18.3'])
  })

  it('drops empty and whitespace-only lines, including blank lines in the middle', () => {
    expect(linesToList('I10\n\n   \nN18.3\n')).toEqual(['I10', 'N18.3'])
  })

  it('handles Windows line endings (the stray \\r is trimmed away)', () => {
    expect(linesToList('I10\r\nN18.3\r\n')).toEqual(['I10', 'N18.3'])
  })

  it('returns an empty array for empty or whitespace-only input', () => {
    expect(linesToList('')).toEqual([])
    expect(linesToList(' \n \n')).toEqual([])
  })

  it('keeps a single line as a one-element array', () => {
    expect(linesToList('Metformin 500mg')).toEqual(['Metformin 500mg'])
  })
})
