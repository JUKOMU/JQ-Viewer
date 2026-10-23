import { computed, ref, shallowRef } from 'vue'

export function useMultiSelect<T extends string | number>() {
  const active = ref(false)
  const selected = shallowRef(new Set<T>())
  const count = computed(() => selected.value.size)

  const enter = (id?: T) => {
    active.value = true
    if (id !== undefined) selected.value = new Set([...selected.value, id])
  }
  const toggle = (id: T) => {
    const next = new Set(selected.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    selected.value = next
  }
  const select = (ids: readonly T[]) => {
    selected.value = new Set(ids)
  }
  const invert = (ids: readonly T[]) => {
    selected.value = new Set(ids.filter((id) => !selected.value.has(id)))
  }
  const clear = () => {
    selected.value = new Set()
  }
  const exit = () => {
    clear()
    active.value = false
  }

  return { active, selected, count, enter, toggle, select, invert, clear, exit }
}
