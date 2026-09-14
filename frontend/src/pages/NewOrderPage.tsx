import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle, XCircle } from 'lucide-react'

import { FormField } from '@/components/forms/FormField'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Textarea } from '@/components/ui/Textarea'
import { orderFormSchema, type OrderFormInput, type OrderFormValues } from '@/utils/orderFormSchema'
import { isValidNPI, isValidICD10 } from '@/utils/validators'

export function NewOrderPage() {
    // useForm (react-hook-form, see glossary) owns all field values + validation
    // state for us. zodResolver plugs our zod schema in as the validation engine.
    // Three generics: the raw values every field holds while typing (Input), an
    // unused context type, and the values handleSubmit hands to our callback
    // once zod has coerced and validated them (Output) -- see OrderFormInput.
    const {
        register,
        handleSubmit,
        watch,
        formState: { errors, touchedFields },
    } = useForm<OrderFormInput, unknown, OrderFormValues>({
        resolver: zodResolver(orderFormSchema),
        mode: 'onBlur', // validate a field as soon as the user leaves it, not only on submit
    })

    // watch() subscribes this component to a field's live value, so the NPI
    // icon updates on every keystroke instead of only after the field loses focus.
    const npiValue = watch('providerNpi')
    const npiTouched = Boolean(touchedFields.providerNpi) || (npiValue?.length ?? 0) === 10
    const npiIsValid = isValidNPI(npiValue ?? '')

    const primaryDiagnosisValue = watch('primaryDiagnosis')
    const icdTouched = Boolean(touchedFields.primaryDiagnosis) || (primaryDiagnosisValue?.length ?? 0) > 0
    const icdIsValid = isValidICD10(primaryDiagnosisValue ?? '')

    // handleSubmit wraps our function: react-hook-form only calls it once
    // validation has passed, and passes the parsed, typed values in.
    const onSubmit = handleSubmit((values) => {
        console.log('form values (submit wiring comes in Part 4):', values)
    })

    return (
        <form onSubmit={onSubmit} className="mx-auto max-w-3xl space-y-8">
            <h1 className="text-2xl font-bold">New Order</h1>

            {/* ---------- Patient Information ---------- */}
            <section className="space-y-4 rounded-lg bg-white p-6 shadow">
                <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500">
                    Patient Information
                </h2>

                <div className="grid grid-cols-2 gap-4">
                    <FormField label="MRN" required error={errors.patientMrn?.message}>
                        <Input {...register('patientMrn')} maxLength={6} placeholder="123456" />
                    </FormField>
                    <FormField label="Date of Birth" error={errors.patientDateOfBirth?.message}>
                        <Input type="date" {...register('patientDateOfBirth')} />
                    </FormField>
                </div>

                <div className="grid grid-cols-2 gap-4">
                    <FormField label="First Name" required error={errors.patientFirstName?.message}>
                        <Input {...register('patientFirstName')} />
                    </FormField>
                    <FormField label="Last Name" required error={errors.patientLastName?.message}>
                        <Input {...register('patientLastName')} />
                    </FormField>
                </div>

                <div className="grid grid-cols-2 gap-4">
                    <FormField label="Sex" error={errors.patientSex?.message}>
                        <Select {...register('patientSex')} defaultValue="">
                            <option value="">Prefer not to say</option>
                            <option value="Male">Male</option>
                            <option value="Female">Female</option>
                            <option value="Other">Other</option>
                        </Select>
                    </FormField>
                    <FormField label="Weight (kg)" error={errors.patientWeightKg?.message as string | undefined}>
                        <Input type="number" step="0.1" {...register('patientWeightKg')} />
                    </FormField>
                </div>

                <FormField label="Allergies" error={errors.patientAllergies?.message}>
                    <Textarea {...register('patientAllergies')} rows={2} />
                </FormField>
            </section>

            {/* ---------- Referring Provider ---------- */}
            <section className="space-y-4 rounded-lg bg-white p-6 shadow">
                <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500">
                    Referring Provider
                </h2>

                <FormField label="Provider Name" required error={errors.providerName?.message}>
                    <Input {...register('providerName')} />
                </FormField>

                <FormField label="NPI" required error={errors.providerNpi?.message}>
                    <div className="flex items-center gap-2">
                        <Input {...register('providerNpi')} maxLength={10} placeholder="1234567890" />
                        {npiTouched &&
                            // Conditional rendering with && -- see glossary.
                            (npiIsValid ? (
                                <CheckCircle className="h-5 w-5 shrink-0 text-green-500" />
                            ) : (
                                <XCircle className="h-5 w-5 shrink-0 text-red-500" />
                            ))}
                    </div>
                </FormField>
            </section>

            {/* ---------- Order Details ---------- */}
            <section className="space-y-4 rounded-lg bg-white p-6 shadow">
                <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500">
                    Order Details
                </h2>

                <FormField label="Medication" required error={errors.medicationName?.message}>
                    <Input {...register('medicationName')} />
                </FormField>

                <FormField label="Primary Diagnosis (ICD-10)" required error={errors.primaryDiagnosis?.message}>
                    <div className="flex items-center gap-2">
                        <Input {...register('primaryDiagnosis')} placeholder="E11.9" />
                        {icdTouched &&
                            (icdIsValid ? (
                                <CheckCircle className="h-5 w-5 shrink-0 text-green-500" />
                            ) : (
                                <XCircle className="h-5 w-5 shrink-0 text-red-500" />
                            ))}
                    </div>
                </FormField>

                <FormField label="Additional Diagnoses" error={errors.additionalDiagnosesText?.message}>
                    <Textarea
                        {...register('additionalDiagnosesText')}
                        rows={3}
                        placeholder={'One ICD-10 code per line, e.g.\nI10\nN18.3'}
                    />
                </FormField>

                <FormField label="Medication History" error={errors.medicationHistoryText?.message}>
                    <Textarea
                        {...register('medicationHistoryText')}
                        rows={3}
                        placeholder={'One medication per line'}
                    />
                </FormField>

                <FormField label="Clinical Notes" error={errors.patientRecords?.message}>
                    <Textarea {...register('patientRecords')} rows={5} />
                </FormField>
            </section>

            <Button type="submit" size="lg">
                Create Order
            </Button>
        </form>
    )
}
