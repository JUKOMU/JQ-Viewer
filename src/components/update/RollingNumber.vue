<template>
  <span class="rolling-number" :aria-label="formattedValue">
    <span class="rolling-value" aria-hidden="true">{{ displayedText }}</span>
  </span>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    value: number
    decimals?: number
  }>(),
  { decimals: 1 },
)

const displayedText = ref('0.0')
const formattedValue = ref('0.0')
let displayedDigits: string | null = null
let targetDigits = ''
let targetValue = 0
let animationDecimals = 1
let animationTimer: ReturnType<typeof setTimeout> | null = null
let animationToken = 0

function decimalPlaces(): number {
  return Math.max(0, Math.floor(props.decimals ?? 1))
}

function normalizeValue(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 0
}

function clearAnimation() {
  animationToken += 1
  if (animationTimer !== null) {
    clearTimeout(animationTimer)
    animationTimer = null
  }
}

function stripLeadingZeros(integerPart: string): string {
  return integerPart.replace(/^0+(?=\d)/, '') || '0'
}

function renderDigits(digits: string): string {
  const separatorIndex = digits.indexOf('.')
  if (separatorIndex < 0) return stripLeadingZeros(digits)

  const integerPart = stripLeadingZeros(digits.slice(0, separatorIndex))
  return `${integerPart}.${digits.slice(separatorIndex + 1)}`
}

function integerWidth(value: string): number {
  const separatorIndex = value.indexOf('.')
  return separatorIndex < 0 ? value.length : separatorIndex
}

function padDigits(value: string, width: number, decimals: number): string {
  const separatorIndex = value.indexOf('.')
  const integerPart = separatorIndex < 0 ? value : value.slice(0, separatorIndex)
  const fractionPart = separatorIndex < 0 ? '' : value.slice(separatorIndex + 1)
  const paddedFraction = decimals > 0 ? fractionPart.padEnd(decimals, '0').slice(0, decimals) : ''
  return integerPart.padStart(width, '0') + (decimals > 0 ? `.${paddedFraction}` : '')
}

function setDisplayedDigits(digits: string) {
  displayedDigits = digits
  displayedText.value = renderDigits(digits)
}

function digitPositions(value: string): number[] {
  return [...value].flatMap((character, index) => (character === '.' ? [] : index))
}

function stepDisplayedDigits() {
  if (displayedDigits === null) return

  const direction = Number.parseFloat(displayedDigits) < targetValue ? 1 : -1
  const current = [...displayedDigits]
  const target = [...targetDigits]
  for (const index of digitPositions(displayedDigits)) {
    const currentDigit = Number(current[index])
    const targetDigit = Number(target[index])
    if (currentDigit !== targetDigit) {
      current[index] = String((currentDigit + direction + 10) % 10)
    }
  }
  setDisplayedDigits(current.join(''))
}

function scheduleAnimation() {
  if (animationTimer !== null || displayedDigits === null || displayedDigits === targetDigits) {
    return
  }

  const token = animationToken
  animationTimer = setTimeout(() => {
    animationTimer = null
    if (token !== animationToken || displayedDigits === null) return

    stepDisplayedDigits()
    if (displayedDigits === targetDigits) return
    scheduleAnimation()
  }, 16)
}

function animateTo(value: number) {
  const decimals = decimalPlaces()
  const normalizedValue = normalizeValue(value)
  const nextTarget = normalizedValue.toFixed(decimals)
  formattedValue.value = nextTarget
  targetValue = normalizedValue

  if (displayedDigits === null || animationDecimals !== decimals) {
    animationDecimals = decimals
    targetDigits = nextTarget
    setDisplayedDigits(nextTarget)
    clearAnimation()
    return
  }

  const width = Math.max(integerWidth(displayedDigits), integerWidth(nextTarget))
  displayedDigits = padDigits(renderDigits(displayedDigits), width, decimals)
  targetDigits = padDigits(nextTarget, width, decimals)
  displayedText.value = renderDigits(displayedDigits)

  if (displayedDigits === targetDigits) {
    clearAnimation()
    return
  }
  scheduleAnimation()
}

watch(
  () => [props.value, props.decimals],
  () => animateTo(props.value),
  { immediate: true },
)

onBeforeUnmount(clearAnimation)
</script>

<style scoped>
.rolling-number {
  display: inline-flex;
  align-items: baseline;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.rolling-value {
  display: inline-block;
  min-width: 1ch;
}
</style>
