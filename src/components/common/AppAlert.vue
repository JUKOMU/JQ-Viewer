<template>
  <section
    class="app-alert"
    :class="[`app-alert--${layout}`, `app-alert--${tone}`]"
    role="alertdialog"
    aria-modal="true"
    :aria-labelledby="titleId"
    :aria-describedby="message ? messageId : undefined"
    v-bind="options.htmlAttributes"
  >
    <header class="app-alert__header">
      <p v-if="options.subHeader" class="app-alert__subheader">{{ options.subHeader }}</p>
      <h2 :id="titleId" class="app-alert__title">{{ options.header }}</h2>
    </header>

    <div class="app-alert__body">
      <p v-if="message" :id="messageId" class="app-alert__message">{{ message }}</p>

      <div v-if="textInputs.length" class="app-alert__fields">
        <label v-for="input in textInputs" :key="input.id" class="app-alert__field">
          <span v-if="input.placeholder" class="app-alert__field-label">{{
            input.placeholder
          }}</span>
          <textarea
            v-if="input.type === 'textarea'"
            :id="input.id"
            class="app-alert__textarea"
            :name="input.name"
            :value="textValue(input)"
            :disabled="input.disabled"
            :tabindex="input.tabindex"
            :aria-label="input.placeholder || input.name"
            v-bind="input.attributes"
            @input="updateTextInput(input, $event)"
          />
          <input
            v-else
            :id="input.id"
            class="app-alert__input"
            :name="input.name"
            :type="input.type || 'text'"
            :value="textValue(input)"
            :min="input.min"
            :max="input.max"
            :disabled="input.disabled"
            :tabindex="input.tabindex"
            :aria-label="input.placeholder || input.name"
            v-bind="input.attributes"
            @input="updateTextInput(input, $event)"
          />
        </label>
      </div>

      <fieldset v-if="radioInputs.length" class="app-alert__choices">
        <legend class="app-alert__sr-only">{{ options.header }}</legend>
        <label
          v-for="input in radioInputs"
          :key="input.id"
          class="app-alert__choice"
          :class="{ 'app-alert__choice--selected': selectedRadio === input.value }"
        >
          <input
            class="app-alert__choice-input"
            type="radio"
            :name="input.name || 'app-alert-choice'"
            :value="input.value"
            :checked="selectedRadio === input.value"
            :disabled="input.disabled"
            :tabindex="input.tabindex"
            @change="selectRadio(input)"
          />
          <span class="app-alert__choice-mark" aria-hidden="true" />
          <span class="app-alert__choice-label">{{ input.label }}</span>
        </label>
      </fieldset>
    </div>

    <footer
      class="app-alert__actions"
      :class="{ 'app-alert__actions--stacked': buttons.length > 2 }"
    >
      <button
        v-for="(button, index) in buttons"
        :key="button.id || `${button.text}-${index}`"
        type="button"
        class="app-alert__button"
        :class="buttonClasses(button)"
        :disabled="busy"
        v-bind="button.htmlAttributes"
        @click="handleButton(button)"
      >
        {{ button.text }}
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { modalController } from '@ionic/vue'
import type {
  AppAlertLayout,
  AppAlertOptions,
  AppAlertRuntime,
  AppAlertTone,
  AlertButton,
  AlertInput,
} from '@/services/AppAlertService'

const props = defineProps<{
  options: AppAlertOptions
  modalId: string
  runtime: AppAlertRuntime
}>()

const layout = computed<AppAlertLayout>(() => props.options.layout ?? 'confirm')
const tone = computed<AppAlertTone>(() => props.options.tone ?? 'default')
const titleId = `${props.modalId}-title`
const messageId = `${props.modalId}-message`
const message = computed(() =>
  typeof props.options.message === 'string' ? props.options.message : '',
)
const inputs = (props.options.inputs ?? []).map((input, index) => ({
  ...input,
  id: input.id ?? `${props.modalId}-input-${index}`,
  name: input.name ?? String(index),
}))
const buttons = (props.options.buttons ?? []).filter(
  (button): button is AlertButton => typeof button !== 'string',
)
const textInputs = computed(() =>
  inputs.filter((input) => input.type !== 'radio' && input.type !== 'checkbox'),
)
const radioInputs = computed(() => inputs.filter((input) => input.type === 'radio'))
const values = reactive<Record<string, unknown>>(
  Object.fromEntries(textInputs.value.map((input) => [input.name, input.value ?? ''])),
)
const selectedRadio = ref(radioInputs.value.find((input) => input.checked)?.value)
const busy = ref(false)

function textValue(input: AlertInput): string | number {
  const value = values[input.name ?? '']
  return typeof value === 'number' || typeof value === 'string' ? value : ''
}

function updateTextInput(input: AlertInput, event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement
  values[input.name ?? ''] = target.value
  const onInput = input.attributes?.onInput
  if (typeof onInput === 'function') onInput(event)
}

function selectRadio(input: AlertInput) {
  selectedRadio.value = input.value
  input.handler?.(input)
}

function collectValues(): unknown {
  const hasText = textInputs.value.length > 0
  const hasRadio = radioInputs.value.length > 0

  if (hasRadio && !hasText) return selectedRadio.value

  const result: Record<string, unknown> = { ...values }
  if (hasRadio) {
    result[radioInputs.value[0].name ?? 'choice'] = selectedRadio.value ?? ''
  }
  return Object.keys(result).length > 0 ? result : undefined
}

function buttonClasses(button: AlertButton): Array<string | Record<string, boolean>> {
  return [
    ...(Array.isArray(button.cssClass)
      ? button.cssClass
      : button.cssClass
        ? [button.cssClass]
        : []),
    {
      'app-alert__button--cancel': button.role === 'cancel',
      'app-alert__button--danger': button.role === 'destructive',
      'app-alert__button--outline': button.role === 'settings',
      'app-alert__button--primary':
        button.role !== 'cancel' && button.role !== 'destructive' && button.role !== 'settings',
    },
  ]
}

async function handleButton(button: AlertButton) {
  if (busy.value) return
  busy.value = true
  props.runtime.setButtonHandled(true)
  const data = collectValues()

  try {
    const result = await button.handler?.(data)
    if (result === false) {
      props.runtime.setButtonHandled(false)
      return
    }
    const dismissData = result && typeof result === 'object' ? result : data
    await modalController.dismiss(dismissData, button.role, props.modalId)
  } catch (error) {
    props.runtime.setButtonHandled(false)
    throw error
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.app-alert {
  display: flex;
  max-height: calc(100vh - 64px);
  flex-direction: column;
  overflow: hidden;
  background: #fffbf8;
  color: #3a261d;
}

.app-alert__header {
  flex: 0 0 auto;
  padding: 20px 20px 10px;
  background: #fff4ec;
}

.app-alert--danger .app-alert__header {
  background: #fce8e4;
}

.app-alert--info .app-alert__header {
  background: #fff0df;
}

.app-alert__subheader {
  margin: 0 0 4px;
  color: #8c6b5a;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0;
}

.app-alert__title {
  margin: 0;
  color: #4c2a18;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}

.app-alert--danger .app-alert__title {
  color: #9f392c;
}

.app-alert__body {
  min-height: 0;
  padding: 10px 20px 20px 20px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.app-alert__message {
  margin: 0;
  color: #6b4e3e;
  font-size: 14px;
  line-height: 1.65;
  letter-spacing: 0;
  overflow-wrap: anywhere;
  white-space: pre-line;
}

.app-alert__fields,
.app-alert__choices {
  margin-top: 14px;
}

.app-alert__fields {
  display: grid;
  gap: 12px;
}

.app-alert__field {
  display: grid;
  gap: 6px;
}

.app-alert__field-label {
  color: #6b4e3e;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0;
}

.app-alert__input,
.app-alert__textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid #e7d7cc;
  border-radius: 10px;
  outline: none;
  background: #fff;
  color: #3a261d;
  font: inherit;
  font-size: 15px;
  letter-spacing: 0;
}

.app-alert__input {
  min-height: 44px;
  padding: 9px 12px;
}

.app-alert__textarea {
  min-height: 144px;
  max-height: 42vh;
  padding: 10px 12px;
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  line-height: 1.5;
}

.app-alert__input:focus,
.app-alert__textarea:focus {
  border-color: #e8843c;
  box-shadow: 0 0 0 3px rgb(232 132 60 / 14%);
}

.app-alert__input:disabled,
.app-alert__textarea:disabled {
  opacity: 0.55;
}

.app-alert__choices {
  max-height: 42vh;
  padding: 0;
  overflow-y: auto;
  border: 1px solid #eadbd1;
  border-radius: 10px;
}

.app-alert__choice {
  position: relative;
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: 12px;
  padding: 0 14px;
  color: #4c2a18;
  cursor: pointer;
}

.app-alert__choice + .app-alert__choice {
  border-top: 1px solid #f0e4da;
}

.app-alert__choice--selected {
  background: rgb(232 132 60 / 8%);
  font-weight: 600;
}

.app-alert__choice-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.app-alert__choice-mark {
  box-sizing: border-box;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  border: 2px solid #a88a78;
  border-radius: 50%;
  background: #fff;
}

.app-alert__choice--selected .app-alert__choice-mark {
  border: 5px solid #e8843c;
}

.app-alert__choice-input:focus-visible + .app-alert__choice-mark {
  outline: 2px solid #e8843c;
  outline-offset: 3px;
}

.app-alert__choice-label {
  min-width: 0;
  font-size: 15px;
  line-height: 1.45;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}

.app-alert__actions {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(auto-fit, minmax(0, 1fr));
  gap: 10px;
  padding: 12px 16px 16px;
  border-top: 1px solid #f0e4da;
  background: #fffbf8;
}

.app-alert__actions--stacked {
  grid-template-columns: 1fr;
}

.app-alert__button {
  min-width: 0;
  min-height: 44px;
  padding: 9px 12px;
  border: 0;
  border-radius: 10px;
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: 0;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.2s ease;
  overflow-wrap: anywhere;
}

.app-alert__button--cancel {
  background: #f5ebe4;
  color: #6b4e3e;
}

.app-alert__button--primary {
  background: #e8843c;
  color: #fff;
}

.app-alert__button--danger {
  background: #d4533e;
  color: #fff;
}

.app-alert__button--outline {
  border: 1px solid #e8843c;
  background: #fff;
  color: #bd652a;
}

.app-alert__button:active {
  transform: translateY(1px);
}

.app-alert__button:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 2px;
}

.app-alert__button:disabled {
  cursor: wait;
  opacity: 0.65;
}

.app-alert__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (prefers-reduced-motion: reduce) {
  .app-alert__button {
    transition: none;
  }
}
</style>
