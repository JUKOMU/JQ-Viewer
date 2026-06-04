module.exports = {
  root: true,
  env: {
    node: true,
  },
  extends: [
    'plugin:vue/vue3-recommended',
    'eslint:recommended',
    '@vue/typescript/recommended',
    'prettier',
  ],
  parserOptions: {
    ecmaVersion: 2020,
  },
  rules: {
    'no-console': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
    'no-debugger': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
    'vue/no-deprecated-slot-attribute': 'off',
    '@typescript-eslint/no-explicit-any': 'off',
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    'vue/define-macros-order': ['warn', {
      order: ['defineOptions', 'defineProps', 'defineEmits', 'defineSlots'],
      defineExposeLast: true,
    }],
    '@typescript-eslint/consistent-type-imports': ['error', {
      prefer: 'type-imports',
    }],
    'vue/multi-word-component-names': 'off',
  },
  overrides: [
    {
      files: ['src/**/*.vue'],
      rules: {
        'no-restricted-imports': ['error', {
          paths: [
            {
              name: '@capacitor/core',
              message: 'Use platform/service listener handle types instead of Capacitor core types in Vue files.',
            },
          ],
        }],
      },
    },
  ],
}
