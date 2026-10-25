import { computed, ref, shallowRef } from 'vue'

export function useMultiSelect<T extends string | number>() {
  const active = ref(false)
  const selected = shallowRef(new Set<T>())
  const count = computed(() => selected.value.size)
  const allSelected = ref(false)

  const setSelected = (next: Set<T>) => {
    if (next.size !== selected.value.size) allSelected.value = false
    selected.value = next
  }

  const enter = (id?: T) => {
    active.value = true
    if (id !== undefined) setSelected(new Set([...selected.value, id]))
  }
  const toggle = (id: T) => {
    const next = new Set(selected.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelected(next)
  }
  const select = (ids: readonly T[]) => {
    setSelected(new Set(ids))
  }
  const selectAll = (ids: readonly T[]) => {
    setSelected(new Set(ids))
    allSelected.value = true
  }
  const invert = (ids: readonly T[]) => {
    setSelected(new Set(ids.filter((id) => !selected.value.has(id))))
  }
  const clear = () => {
    setSelected(new Set())
    allSelected.value = false
  }
  const exit = () => {
    clear()
    active.value = false
  }

  return {
    active,
    selected,
    count,
    allSelected,
    enter,
    toggle,
    select,
    selectAll,
    invert,
    clear,
    exit,
  }
}
