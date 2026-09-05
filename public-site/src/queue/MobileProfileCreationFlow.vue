<script setup>
import { ArrowLeft, Check, RefreshCw, Smartphone, TriangleAlert, X } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import MobileProfileSettings from './MobileProfileSettings.vue'

const props = defineProps({
  token: { type: String, required: true }
})

const QUEUE_API_URL = import.meta.env.VITE_QUEUE_STATUS_API_URL ||
  (typeof window !== 'undefined' ? `${window.location.origin}/api/queue-status` : '/api/queue-status')
const PLAYER_PROFILE_CREATION_API_BASE = import.meta.env.VITE_PLAYER_PROFILE_CREATION_API_BASE ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'player-profile-creation/sessions')

const DEFAULT_SETTINGS = {
  qq_visibility: 'PUBLIC_WEBSITE',
  notification_enabled: true,
  notify_queue_changes: true,
  notify_playing_position: false,
  notify_online_check_in: true,
  notify_absence: true,
  notify_machine_status: false
}

const step = ref('LOADING')
const session = ref(null)
const errorDetail = ref('')
const resultDetail = ref('')
const submitting = ref(false)
const draft = reactive(createDraft())

const sessionEndpoint = computed(() => (
  `${PLAYER_PROFILE_CREATION_API_BASE}/${encodeURIComponent(props.token)}`
))

const formValid = computed(() => (
  codePointLength(draft.nickname.trim()) >= 1 &&
  codePointLength(draft.nickname.trim()) <= 18 &&
  /^\d{5,12}$/.test(draft.qq_number.trim()) &&
  ['MALE', 'FEMALE', 'UNDISCLOSED'].includes(draft.gender) &&
  ['SOLO', 'OPEN_TO_JOIN', 'ASK_EVERY_TIME'].includes(draft.default_preference) &&
  draft.password.length >= 8 &&
  draft.password.length <= 128 &&
  draft.password === draft.password_confirmation
))

function createDraft() {
  return {
    nickname: '',
    gender: 'UNDISCLOSED',
    default_preference: 'ASK_EVERY_TIME',
    qq_number: '',
    password: '',
    password_confirmation: '',
    ...DEFAULT_SETTINGS
  }
}

function codePointLength(value) {
  return [...String(value || '')].length
}

function updateSetting({ key, value }) {
  if (Object.prototype.hasOwnProperty.call(draft, key)) draft[key] = value
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    cache: 'no-store',
    credentials: 'include',
    headers: { Accept: 'application/json', ...(options.headers || {}) },
    ...options
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(payload.error || '请求暂时无法完成。')
    error.code = payload.code
    error.status = response.status
    throw error
  }
  return payload
}

async function initialize() {
  step.value = 'LOADING'
  errorDetail.value = ''
  try {
    session.value = await requestJson(sessionEndpoint.value)
    if (session.value.status !== 'OPEN') {
      step.value = 'ERROR'
      errorDetail.value = '这次玩家资料创建已经完成，请直接使用 QQ 和密码登录。'
      return
    }
    step.value = 'FORM'
  } catch (error) {
    step.value = error?.status >= 500 ? 'ERROR' : 'REJECTED'
    errorDetail.value = error?.message || '无法打开玩家资料创建页面。'
  }
}

async function submit() {
  if (submitting.value || !formValid.value) return
  if (draft.password !== draft.password_confirmation) {
    errorDetail.value = '两次输入的密码不一致。'
    return
  }
  submitting.value = true
  step.value = 'SUBMITTING'
  errorDetail.value = ''
  try {
    const payload = {
      nickname: draft.nickname.trim(),
      gender: draft.gender,
      default_preference: draft.default_preference,
      qq_number: draft.qq_number.trim(),
      password: draft.password,
      password_confirmation: draft.password_confirmation,
      notification_enabled: draft.notification_enabled,
      notify_queue_changes: draft.notify_queue_changes,
      notify_playing_position: draft.notify_playing_position,
      notify_online_check_in: draft.notify_online_check_in,
      notify_absence: draft.notify_absence,
      notify_machine_status: draft.notify_machine_status
    }
    const result = await requestJson(`${sessionEndpoint.value}/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    resultDetail.value = result.account?.profile?.nickname
      ? `“${result.account.profile.nickname}”已经创建并绑定网页账户。`
      : '玩家资料已经创建并绑定网页账户。'
    step.value = 'SUCCESS'
  } catch (error) {
    if (['QQ_ALREADY_USED', 'NICKNAME_ALREADY_USED', 'PASSWORD_MISMATCH', 'PASSWORD_TOO_SHORT', 'PASSWORD_TOO_LONG'].includes(error?.code)) {
      step.value = 'FORM'
      errorDetail.value = error.message
    } else {
      step.value = error?.status >= 500 ? 'SUBMIT_ERROR' : 'REJECTED'
      resultDetail.value = error?.message || '玩家资料创建未能完成。'
    }
  } finally {
    submitting.value = false
  }
}

function leaveFlow() {
  const url = new URL(window.location.href)
  url.searchParams.delete('player_profile_creation')
  window.location.assign(`${url.pathname}${url.search}${url.hash}`)
}

function genderLabel(value) {
  if (value === 'MALE') return '男'
  if (value === 'FEMALE') return '女'
  return '不显示'
}

function preferenceLabel(value) {
  if (value === 'SOLO') return '单人游玩'
  if (value === 'OPEN_TO_JOIN') return '允许他人加入'
  return '每次询问'
}

onMounted(initialize)
onBeforeUnmount(() => {})
</script>

<template>
  <Teleport to="body">
    <div class="profile-creation-shell">
      <main class="profile-creation-page">
        <header class="profile-creation-header">
          <div class="profile-creation-brand">
            <span aria-hidden="true"><Smartphone :size="22" /></span>
            <div>
              <h1>创建网页玩家资料</h1>
              <p v-if="session?.venue?.name">{{ session.venue.name }}</p>
              <p v-else>连接现场玩家资料库</p>
            </div>
          </div>
          <button type="button" title="返回队列" aria-label="返回队列" @click="leaveFlow">
            <X :size="20" aria-hidden="true" />
          </button>
        </header>

        <section v-if="step === 'LOADING' || step === 'SUBMITTING'" class="profile-creation-result" aria-live="polite">
          <span class="is-progress" aria-hidden="true"><RefreshCw :size="25" /></span>
          <h2>{{ step === 'LOADING' ? '正在准备创建页面' : '正在创建网页玩家资料' }}</h2>
          <p>{{ step === 'LOADING' ? '请稍候。' : '请保持页面打开，不要重复提交。' }}</p>
        </section>

        <section v-else-if="step === 'FORM'" class="profile-creation-content">
          <section class="profile-creation-intro">
            <span>来自现场终端</span>
            <h2>填写玩家资料并设置密码</h2>
            <p>完成后会上传到当前机厅的玩家资料库，并立即绑定网页账户。以后可以用 QQ 和密码登录网页管理资料。</p>
          </section>
          <form class="profile-creation-form" @submit.prevent="submit">
            <label><span>玩家昵称</span><input v-model="draft.nickname" maxlength="18" autocomplete="nickname" placeholder="输入现场容易辨认的昵称" /></label>
            <fieldset><legend>性别</legend><div class="profile-choice-row is-three">
              <button v-for="value in ['MALE', 'FEMALE', 'UNDISCLOSED']" :key="value" type="button"
                :class="{ active: draft.gender === value }" @click="draft.gender = value">{{ genderLabel(value) }}</button>
            </div></fieldset>
            <label><span>QQ 号</span><input v-model="draft.qq_number" inputmode="numeric" maxlength="12" autocomplete="username" placeholder="5 至 12 位数字" @input="draft.qq_number = draft.qq_number.replace(/\D/g, '').slice(0, 12)" /><small>QQ 用于网页登录、现场联系和排队通知。</small></label>
            <fieldset><legend>默认游玩偏好</legend><div class="profile-choice-row is-three">
              <button v-for="value in ['OPEN_TO_JOIN', 'SOLO', 'ASK_EVERY_TIME']" :key="value" type="button"
                :class="{ active: draft.default_preference === value }" @click="draft.default_preference = value">{{ preferenceLabel(value) }}</button>
            </div></fieldset>
            <label><span>账户密码</span><input v-model="draft.password" type="password" minlength="8" maxlength="128" autocomplete="new-password" placeholder="至少 8 个字符" /><small>请使用本人能够记住的密码；网页端暂不提供仅凭 QQ 的自助找回。</small></label>
            <label><span>再次输入密码</span><input v-model="draft.password_confirmation" type="password" minlength="8" maxlength="128" autocomplete="new-password" placeholder="再次输入账户密码" /></label>
            <p class="profile-creation-fixed-note">网页账户绑定后，QQ 将允许在网站玩家资料中显示；现场终端默认不能编辑这份资料，之后可由玩家在网页设置中重新开放。</p>
            <MobileProfileSettings :settings="draft" :show-visibility="false" @change="updateSetting" />
            <p v-if="errorDetail" class="profile-form-error" role="alert">{{ errorDetail }}</p>
            <button class="profile-primary" type="submit" :disabled="!formValid || submitting"><Check :size="17" />确认创建并绑定</button>
          </form>
        </section>

        <section v-else-if="step === 'SUCCESS'" class="profile-creation-result is-success" aria-live="polite">
          <span aria-hidden="true"><Check :size="28" /></span>
          <h2>网页玩家资料已创建</h2>
          <p>{{ resultDetail }}</p>
          <p>当前浏览器已经登录该网页账户，可以返回队列继续操作。</p>
          <button class="profile-primary" type="button" @click="leaveFlow">返回队列</button>
        </section>

        <section v-else class="profile-creation-result is-error" aria-live="assertive">
          <span aria-hidden="true"><TriangleAlert :size="25" /></span>
          <h2>{{ step === 'SUBMIT_ERROR' ? '暂时无法确认创建结果' : '无法继续创建网页玩家资料' }}</h2>
          <p>{{ step === 'SUBMIT_ERROR' ? resultDetail : errorDetail }}</p>
          <button class="profile-primary" type="button" @click="initialize">重新打开</button>
          <button class="profile-secondary" type="button" @click="leaveFlow">返回队列</button>
        </section>
      </main>
    </div>
  </Teleport>
</template>

<style scoped>
.profile-creation-shell { min-height: 100vh; background: #f5f5f7; color: #1d1d1f; }
.profile-creation-page { width: min(680px, 100%); min-height: 100vh; margin: 0 auto; padding: 18px 16px calc(32px + env(safe-area-inset-bottom)); }
.profile-creation-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.profile-creation-brand { display: flex; align-items: center; gap: 11px; min-width: 0; }
.profile-creation-brand > span { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 50%; color: #007aff; background: #e8f2ff; }
.profile-creation-brand h1 { margin: 0; font-size: 19px; line-height: 1.3; }
.profile-creation-brand p { margin: 3px 0 0; color: #6e6e73; font-size: 11px; }
.profile-creation-header > button { width: 42px; height: 42px; display: grid; place-items: center; border: 0; border-radius: 50%; color: #6e6e73; background: transparent; cursor: pointer; }
.profile-creation-header > button:hover { background: #e5e5ea; }
.profile-creation-content { padding-top: 22px; }
.profile-creation-intro { padding: 18px 0 16px; }
.profile-creation-intro > span { color: #007aff; font-size: 11px; font-weight: 650; }
.profile-creation-intro h2 { margin: 6px 0 0; font-size: 24px; line-height: 1.3; }
.profile-creation-intro p { margin: 9px 0 0; color: #6e6e73; font-size: 12px; line-height: 1.65; }
.profile-creation-form { display: grid; gap: 15px; }
.profile-creation-form label > span, .profile-creation-form legend { display: block; margin-bottom: 7px; color: #6e6e73; font-size: 12px; font-weight: 650; }
.profile-creation-form input { width: 100%; height: 48px; border: 1px solid #d2d2d7; border-radius: 8px; padding: 0 13px; color: #1d1d1f; background: #fff; font-size: 15px; }
.profile-creation-form label small { display: block; margin-top: 5px; color: #8e8e93; font-size: 10px; line-height: 1.5; }
.profile-creation-form fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.profile-choice-row { display: grid; gap: 7px; }
.profile-choice-row.is-three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.profile-choice-row button { min-height: 45px; border: 1px solid #d2d2d7; border-radius: 8px; color: #1d1d1f; background: #fff; cursor: pointer; font-size: 12px; }
.profile-choice-row button.active { border-color: #007aff; color: #007aff; background: #e8f2ff; font-weight: 650; }
.profile-creation-fixed-note { margin: -2px 0 0; padding: 11px 12px; border-left: 3px solid #007aff; color: #6e6e73; background: #e8f2ff; font-size: 10px; line-height: 1.6; }
.profile-form-error { margin: 0; color: #c9342e; font-size: 12px; line-height: 1.55; }
.profile-primary, .profile-secondary { width: 100%; min-height: 48px; display: flex; align-items: center; justify-content: center; gap: 8px; border-radius: 8px; padding: 0 15px; cursor: pointer; font-size: 14px; font-weight: 700; }
.profile-primary { border: 0; color: #fff; background: #007aff; }
.profile-secondary { margin-top: 9px; border: 1px solid #d2d2d7; color: #007aff; background: transparent; }
.profile-primary:disabled { opacity: .5; cursor: default; }
.profile-creation-result { min-height: calc(100vh - 90px); display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 36px 10px; text-align: center; }
.profile-creation-result > span { width: 58px; height: 58px; display: grid; place-items: center; border-radius: 50%; color: #007aff; background: #e8f2ff; }
.profile-creation-result.is-success > span { color: #247a3e; background: #eaf8ef; }
.profile-creation-result.is-error > span { color: #c9342e; background: #fff1f0; }
.profile-creation-result h2 { margin: 18px 0 0; font-size: 22px; }
.profile-creation-result p { max-width: 430px; margin: 9px 0 0; color: #6e6e73; font-size: 12px; line-height: 1.65; }
.profile-creation-result .profile-primary { max-width: 430px; margin-top: 23px; }
.profile-creation-result .profile-secondary { max-width: 430px; }
.is-progress svg { animation: profile-creation-spin 1s linear infinite; }
@keyframes profile-creation-spin { to { transform: rotate(360deg); } }
@media (max-width: 520px) {
  .profile-creation-page { padding-top: 14px; }
  .profile-creation-intro h2 { font-size: 21px; }
  .profile-choice-row.is-three { grid-template-columns: 1fr; }
}
</style>
