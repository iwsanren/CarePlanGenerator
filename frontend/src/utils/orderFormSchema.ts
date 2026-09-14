import { z } from 'zod'
import { NPI_REGEX, MRN_REGEX, ICD10_REGEX } from './validators'

export const orderFormSchema = z.object({
    patientFirstName: z.string().min(1, 'First name is required'),
    patientLastName: z.string().min(1, 'Last name is required'),
    patientMrn: z.string().regex(MRN_REGEX, 'MRN must be exactly 6 digits'),
    patientDateOfBirth: z
        .string()
        .optional()
        .refine((v) => !v || new Date(v) <= new Date(), 'Date of birth cannot be in the future'),
    patientSex: z.enum(['Male', 'Female', 'Other']).optional().or(z.literal('')),
    patientWeightKg: z
        .union([z.coerce.number().positive().max(500), z.literal('')])
        .optional(),
    patientAllergies: z.string().optional(),

    providerName: z.string().min(1, 'Provider name is required'),
    providerNpi: z.string().regex(NPI_REGEX, 'NPI must be exactly 10 digits'),

    medicationName: z.string().min(1, 'Medication name is required'),
    primaryDiagnosis: z.string().regex(ICD10_REGEX, 'Must be a valid ICD-10 code (e.g. E11.9)'),
    additionalDiagnosesText: z.string().optional(), // raw textarea; split at submit time
    medicationHistoryText: z.string().optional(),   // raw textarea; split at submit time
    patientRecords: z.string().optional(),
})

export type OrderFormValues = z.infer<typeof orderFormSchema>
