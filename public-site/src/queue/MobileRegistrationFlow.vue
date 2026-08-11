<script setup>
import {
  ArrowLeft,
  Check,
  CircleCheck,
  Clock3,
  RefreshCw,
  Search,
  Smartphone,
  TriangleAlert,
  UserPlus,
  X
} from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import MobileProfileSettings from './MobileProfileSettings.vue'
import { normalizeMachineConfiguration } from './machineConfiguration.js'

const props = defineProps({
  token: { type: String, required: true }
})

const QUEUE_API_URL = import.meta.env.VITE_QUEUE_STATUS_API_URL ||
  (typeof window !== 'undefined' ? `${window.location.origin}/api/queue-status` : '/api/queue-status')
const MOBILE_API_BASE = import.meta.env.VITE_QUEUE_MOBILE_API_BASE ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'queue-mobile/sessions')
const PROFILE_COOKIE = 'maimai_q_mobile_profile'
const RESULT_POLL_INTERVAL = 1500
const DEFAULT_NOTIFICATIONS = Object.freeze({
  notification_enabled: true,
  notify_queue_changes: true,
  notify_playing_position: false,
  notify_online_check_in: true,
  notify_absence: true,
  notify_machine_status: false
})
const PROFILE_FORM_ERROR_CODES = new Set([
  'QQ_ALREADY_USED',
  'NICKNAME_ALREADY_USED',
  'NICKNAME_IN_QUEUE',
  'PROFILE_SETTINGS_INVALID'
])
const step = ref('LOADING')
const session = ref(null)
const profiles = ref([])
const botQq = ref(null)
const selectedProfile = ref(null)
const searchText = ref('')
const searching = ref(false)
const errorDetail = ref('')
const resultDetail = ref('')
const requestId = ref(createRequestId())
const rememberedProfileId = ref(readRememberedProfileId())
const selectedPreference = ref(null)
const draftMode = ref(null)
const completionDraft = reactive(createCompletionDraft())
const newProfileDraft = reactive(createNewProfileDraft())
let searchTimer
let resultTimer
let searchSequence = 0

const sessionEndpoint = computed(() => (
  `${MOBILE_API_BASE}/${encodeURIComponent(props.token)}`
))

const displayedProfiles = computed(() => {
  const remembered = rememberedProfileId.value
  return [...profiles.value].sort((first, second) => {
    if (first.profileId === remembered) return -1
    if (second.profileId === remembered) return 1
    return 0
  })
})

const confirmationProfile = computed(() => {
  if (draftMode.value === 'NEW') {
    return {
      nickname: newProfileDraft.nickname.trim(),
      gender: newProfileDraft.gender,
      defaultPreference: newProfileDraft.default_preference,
      qqNumber: newProfileDraft.qq_number.trim(),
      setupComplete: true
    }
  }
  return selectedProfile.value
})

const machineConfiguration = computed(() => normalizeMachineConfiguration(
  session.value?.machine_configuration ?? session.value?.machineConfiguration,
  {
    id: session.value?.machine_id ?? session.value?.machineId,
    name: session.value?.machine_name ?? session.value?.machineName
  }
))

const singlePlayerMachine = computed(() => machineConfiguration.value.capacity === 1)

const needsCurrentPreference = computed(() => (
  !singlePlayerMachine.value &&
  confirmationProfile.value?.defaultPreference === 'ASK_EVERY_TIME'
))

const newProfileValid = computed(() => (
  inRange(codePointLength(newProfileDraft.nickname.trim()), [1, 18]) &&
  /^\d{5,12}$/.test(newProfileDraft.qq_number.trim()) &&
  ['MALE', 'FEMALE', 'UNDISCLOSED'].includes(newProfileDraft.gender) &&
  ['SOLO', 'OPEN_TO_JOIN', 'ASK_EVERY_TIME'].includes(newProfileDraft.default_preference)
))

const completionValid = computed(() => (
  selectedProfile.value?.qqPresent || /^\d{5,12}$/.test(completionDraft.qq_number.trim())
))

function inRange(value, [minimum, maximum]) {
  return value >= minimum && value <= maximum
}

function createCompletionDraft() {
  return {
    qq_number: '',
    qq_visibility: 'TERMINAL_ONLY',
    ...DEFAULT_NOTIFICATIONS
  }
}

function createNewProfileDraft() {
  return {
    nickname: '',
    gender: 'UNDISCLOSED',
    default_preference: 'ASK_EVERY_TIME',
    qq_number: '',
    qq_visibility: 'TERMINAL_ONLY',
    ...DEFAULT_NOTIFICATIONS
  }
}

function resetDraft(target, source) {
  Object.keys(target).forEach((key) => delete target[key])
  Object.assign(target, source)
}

function updateDraft(target, { key, value }) {
  if (Object.prototype.hasOwnProperty.call(target, key)) target[key] = value
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16)
    const value = character === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}

function codePointLength(value) {
  return [...String(value || '')].length
}

function readRememberedProfileId() {
  if (typeof document === 'undefined') return null
  const item = document.cookie.split(';').map((part) => part.trim()).find((part) => (
    part.startsWith(`${PROFILE_COOKIE}=`)
  ))
  if (!item) return null
  try {
    return decodeURIComponent(item.slice(PROFILE_COOKIE.length + 1)) || null
  } catch {
    return null
  }
}

function rememberProfile(profileId) {
  if (!profileId || typeof document === 'undefined') return
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${PROFILE_COOKIE}=${encodeURIComponent(profileId)}` +
    `; Max-Age=31536000; Path=/; SameSite=Lax${secure}`
  rememberedProfileId.value = profileId
}

function forgetRememberedProfile() {
  if (typeof document === 'undefined') return
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${PROFILE_COOKIE}=; Max-Age=0; Path=/; SameSite=Lax${secure}`
  rememberedProfileId.value = null
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    cache: 'no-store',
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

function normalizeProfile(source) {
  if (!source?.profile_id || !source?.nickname) return null
  return {
    profileId: source.profile_id,
    nickname: source.nickname,
    gender: source.gender || 'UNDISCLOSED',
    defaultPreference: source.default_preference,
    qqNumber: source.qq_number || null,
    qqPresent: source.qq_present === true,
    qqPublic: source.qq_public === true,
    notificationSettings: {
      notification_enabled: source.notification_enabled !== false,
      notify_queue_changes: source.notify_queue_changes !== false,
      notify_playing_position: source.notify_playing_position === true,
      notify_online_check_in: source.notify_online_check_in !== false,
      notify_absence: source.notify_absence !== false,
      notify_machine_status: source.notify_machine_status === true
    },
    setupComplete: source.setup_complete === true,
    revision: Number(source.profile_revision) || 1,
    usageCount: Number(source.usage_count) || 0,
    lastUsedAt: Number(source.last_used_at) || null
  }
}

async function initialize() {
  step.value = 'LOADING'
  errorDetail.value = ''
  try {
    const result = await requestJson(`${sessionEndpoint.value}/result`)
    if (result.status !== 'OPEN') {
      applyCommandResult(result)
      return
    }
    const loaded = await loadProfiles('')
    if (!loaded) return
    const rememberedProfile = profiles.value.find((profile) => (
      profile.profileId === rememberedProfileId.value
    ))
    if (rememberedProfile) {
      selectProfile(rememberedProfile)
      return
    }
    if (rememberedProfileId.value) forgetRememberedProfile()
    step.value = 'SELECT'
  } catch (error) {
    if (isTemporaryRequestError(error)) {
      step.value = 'ERROR'
      errorDetail.value = error?.message || '无法打开这次移动设备登记。'
    } else {
      step.value = 'REJECTED'
      resultDetail.value = error?.message || '这次移动设备登记已经结束。'
    }
  }
}

async function loadProfiles(query) {
  const sequence = ++searchSequence
  searching.value = true
  try {
    const url = new URL(sessionEndpoint.value, window.location.href)
    if (query.trim()) url.searchParams.set('q', query.trim())
    const payload = await requestJson(url)
    if (sequence !== searchSequence) return
    session.value = payload.session || null
    botQq.value = payload.bot_qq || null
    profiles.value = Array.isArray(payload.profiles)
      ? payload.profiles.map(normalizeProfile).filter(Boolean)
      : []
    const canonicalRememberedProfile = payload.profile_aliases?.[rememberedProfileId.value]
    if (
      canonicalRememberedProfile &&
      profiles.value.some((profile) => profile.profileId === canonicalRememberedProfile)
    ) {
      rememberProfile(canonicalRememberedProfile)
    }
    errorDetail.value = ''
    return true
  } catch (error) {
    if (sequence !== searchSequence) return
    errorDetail.value = error?.message || '玩家资料库暂时无法读取。'
    if (!session.value) step.value = 'ERROR'
    return false
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

function selectProfile(profile) {
  selectedProfile.value = profile
  selectedPreference.value = singlePlayerMachine.value
    ? 'SOLO'
    : profile.defaultPreference === 'ASK_EVERY_TIME'
    ? null
    : profile.defaultPreference
  errorDetail.value = ''
  if (profile.setupComplete) {
    draftMode.value = 'EXISTING'
    step.value = 'CONFIRM'
    return
  }
  resetDraft(completionDraft, {
    ...createCompletionDraft(),
    ...profile.notificationSettings,
    qq_visibility: profile.qqPublic ? 'PUBLIC_WEBSITE' : 'TERMINAL_ONLY'
  })
  draftMode.value = 'COMPLETE'
  step.value = 'COMPLETE'
}

function beginNewProfile() {
  selectedProfile.value = null
  selectedPreference.value = null
  requestId.value = createRequestId()
  resetDraft(newProfileDraft, createNewProfileDraft())
  draftMode.value = 'NEW'
  step.value = 'NEW'
  errorDetail.value = ''
}

function continueNewProfile() {
  if (!newProfileValid.value) return
  selectedPreference.value = singlePlayerMachine.value
    ? 'SOLO'
    : newProfileDraft.default_preference === 'ASK_EVERY_TIME'
    ? null
    : newProfileDraft.default_preference
  errorDetail.value = ''
  step.value = 'CONFIRM'
}

function continueProfileCompletion() {
  if (!completionValid.value) return
  errorDetail.value = ''
  step.value = 'CONFIRM'
}

function returnFromConfirmation() {
  if (draftMode.value === 'NEW') step.value = 'NEW'
  else if (draftMode.value === 'COMPLETE') step.value = 'COMPLETE'
  else step.value = 'SELECT'
}

function notificationPayload(draft) {
  return {
    qq_visibility: draft.qq_visibility,
    notification_enabled: draft.notification_enabled,
    notify_queue_changes: draft.notify_queue_changes,
    notify_playing_position: draft.notify_playing_position,
    notify_online_check_in: draft.notify_online_check_in,
    notify_absence: draft.notify_absence,
    notify_machine_status: draft.notify_machine_status
  }
}

async function submitRegistration() {
  const profile = confirmationProfile.value
  const preference = singlePlayerMachine.value
    ? 'SOLO'
    : needsCurrentPreference.value
    ? selectedPreference.value
    : profile?.defaultPreference
  if (!profile || !['SOLO', 'OPEN_TO_JOIN'].includes(preference)) {
    errorDetail.value = '请选择本次游玩偏好。'
    return
  }
  const payload = {
    request_id: requestId.value,
    preference
  }
  if (draftMode.value === 'NEW') {
    payload.new_profile = {
      nickname: newProfileDraft.nickname.trim(),
      gender: newProfileDraft.gender,
      default_preference: newProfileDraft.default_preference,
      qq_number: newProfileDraft.qq_number.trim(),
      ...notificationPayload(newProfileDraft)
    }
  } else {
    payload.profile_id = selectedProfile.value.profileId
    payload.expected_profile_revision = selectedProfile.value.revision
    if (draftMode.value === 'COMPLETE') {
      payload.profile_completion = notificationPayload(completionDraft)
      if (!selectedProfile.value.qqPresent) {
        payload.profile_completion.qq_number = completionDraft.qq_number.trim()
      }
    }
  }

  step.value = 'SUBMITTING'
  errorDetail.value = ''
  try {
    const result = await requestJson(`${sessionEndpoint.value}/submit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    applyCommandResult(result)
  } catch (error) {
    if (PROFILE_FORM_ERROR_CODES.has(error?.code) && draftMode.value === 'NEW') {
      step.value = 'NEW'
      errorDetail.value = error.message
      return
    }
    if (PROFILE_FORM_ERROR_CODES.has(error?.code) && draftMode.value === 'COMPLETE') {
      step.value = 'COMPLETE'
      errorDetail.value = error.message
      return
    }
    if (['PLAYER_ALREADY_REGISTERED', 'PLAYER_OPERATION_PENDING'].includes(error?.code)) {
      step.value = 'CONFIRM'
      errorDetail.value = error.message
      return
    }
    if (isTemporaryRequestError(error)) {
      step.value = 'SUBMIT_ERROR'
      resultDetail.value = error?.message || '暂时无法确认登记是否已经提交。'
    } else {
      step.value = 'ERROR'
      errorDetail.value = error?.message || '这次登记未能提交，请重新核对资料。'
    }
  }
}

function applyCommandResult(result) {
  window.clearTimeout(resultTimer)
  resultTimer = null
  const status = result?.status
  if (status === 'PENDING' || status === 'CLAIMED') {
    step.value = 'PENDING'
    resultDetail.value = '正在等待现场终端确认。'
    resultTimer = window.setTimeout(pollResult, RESULT_POLL_INTERVAL)
    return
  }
  if (status === 'APPLIED') {
    if (result.profile_id) rememberProfile(result.profile_id)
    step.value = 'SUCCESS'
    resultDetail.value = result.result_detail || '已通过移动设备加入排队。'
    return
  }
  if (status === 'REJECTED' || status === 'TIMED_OUT' || status === 'UNAVAILABLE') {
    step.value = 'REJECTED'
    resultDetail.value = result.result_detail || '现场终端没有执行这次登记。'
    return
  }
  step.value = 'ERROR'
  errorDetail.value = '这次移动设备登记的状态无法识别，请在终端重新打开。'
}

async function pollResult() {
  try {
    applyCommandResult(await requestJson(`${sessionEndpoint.value}/result`))
  } catch (error) {
    if (isTemporaryRequestError(error)) {
      step.value = 'RESULT_ERROR'
      resultDetail.value = error?.message || '暂时无法读取终端处理结果。'
    } else {
      step.value = 'REJECTED'
      resultDetail.value = error?.message || '这次移动设备登记已经结束。'
    }
  }
}

function isTemporaryRequestError(error) {
  return !Number.isInteger(error?.status) || error.status >= 500
}

function retryResult() {
  step.value = 'PENDING'
  resultDetail.value = '正在重新读取现场终端的处理结果。'
  pollResult()
}

function leaveFlow() {
  const url = new URL(window.location.href)
  url.searchParams.delete('mobile_registration')
  window.location.assign(`${url.pathname}${url.search}${url.hash}`)
}

function preferenceLabel(value) {
  if (value === 'SOLO') return '单人游玩'
  if (value === 'OPEN_TO_JOIN') return '允许他人加入'
  return '每次询问'
}

function genderLabel(value) {
  if (value === 'MALE') return '男'
  if (value === 'FEMALE') return '女'
  return '不显示'
}

function genderSymbol(value) {
  if (value === 'MALE') return '♂'
  if (value === 'FEMALE') return '♀'
  return ''
}

function recentUsageText(profile) {
  if (!profile.lastUsedAt) return profile.usageCount ? `已使用 ${profile.usageCount} 次` : '尚未使用'
  return `${new Date(profile.lastUsedAt).toLocaleDateString('zh-CN')} 使用过`
}

watch(searchText, (value) => {
  window.clearTimeout(searchTimer)
  if (step.value !== 'SELECT') return
  searchTimer = window.setTimeout(() => loadProfiles(value), 280)
})

onMounted(initialize)
onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  window.clearTimeout(resultTimer)
})
</script>

<template>
  <Teleport to="body">
    <div class="mobile-registration-shell">
      <main class="mobile-registration-page">
        <header class="mobile-registration-header">
          <div class="mobile-registration-brand">
            <span aria-hidden="true"><Smartphone :size="22" /></span>
            <div>
              <h1>使用移动设备登记</h1>
              <p v-if="session">{{ session.machine_name }}</p>
              <p v-else>连接现场排队终端</p>
            </div>
          </div>
          <button type="button" title="返回队列" aria-label="返回队列" @click="leaveFlow">
            <X :size="20" aria-hidden="true" />
          </button>
        </header>

        <section v-if="step === 'LOADING' || step === 'SUBMITTING'" class="mobile-registration-result" aria-live="polite">
          <span class="is-progress" aria-hidden="true"><RefreshCw :size="25" /></span>
          <h2>{{ step === 'LOADING' ? '正在读取玩家资料库' : '正在提交登记' }}</h2>
          <p>{{ step === 'LOADING' ? '请稍候。' : '请保持页面打开，重复提交不会建立多份登记。' }}</p>
        </section>

        <section v-else-if="step === 'ERROR'" class="mobile-registration-result is-error" aria-live="assertive">
          <span aria-hidden="true"><TriangleAlert :size="25" /></span>
          <h2>无法继续移动设备登记</h2>
          <p>{{ errorDetail }}</p>
          <button class="mobile-primary" type="button" @click="initialize">重试</button>
          <button class="mobile-secondary" type="button" @click="leaveFlow">返回队列</button>
        </section>

        <template v-else-if="step === 'SELECT'">
          <section class="mobile-registration-intro">
            <div>
              <span>现场登记</span>
              <h2>选择玩家资料</h2>
              <p>从完整的玩家资料库中选择。本浏览器会记住最后成功使用的资料，下次直接进入确认页面，仍可返回改选其他玩家。</p>
            </div>
            <button type="button" @click="beginNewProfile"><UserPlus :size="17" aria-hidden="true" />新建玩家资料</button>
          </section>
          <label class="mobile-search">
            <Search :size="18" aria-hidden="true" />
            <input v-model="searchText" type="search" maxlength="32" autocomplete="off" placeholder="搜索昵称或 QQ 号" />
            <RefreshCw v-if="searching" :size="16" class="is-spinning" aria-label="正在搜索" />
          </label>
          <p v-if="errorDetail" class="mobile-form-error" role="alert">{{ errorDetail }}</p>
          <div v-if="displayedProfiles.length" class="mobile-profile-grid">
            <button v-for="profile in displayedProfiles" :key="profile.profileId" type="button"
              :class="{ 'is-remembered': profile.profileId === rememberedProfileId }" @click="selectProfile(profile)">
              <span v-if="profile.profileId === rememberedProfileId" class="mobile-profile-remembered">上次使用</span>
              <strong>{{ profile.nickname }}<em :class="`is-${profile.gender.toLowerCase()}`">{{ genderSymbol(profile.gender) }}</em></strong>
              <small>{{ preferenceLabel(profile.defaultPreference) }}·{{ recentUsageText(profile) }}</small>
              <small v-if="profile.qqNumber">QQ：{{ profile.qqNumber }}</small>
              <span v-if="!profile.setupComplete" class="mobile-profile-incomplete">需要补全资料</span>
            </button>
          </div>
          <div v-else class="mobile-empty">
            <strong>{{ searching ? '正在搜索' : '没有找到玩家资料' }}</strong>
            <p>{{ searching ? '请稍候。' : '可以更换关键词，或新建一份玩家资料。' }}</p>
          </div>
        </template>

        <template v-else-if="step === 'NEW'">
          <section class="mobile-step-heading">
            <button type="button" aria-label="返回选择玩家资料" @click="step = 'SELECT'"><ArrowLeft :size="19" /></button>
            <div><span>新建资料</span><h2>填写玩家资料</h2><p>所有设置会保存到现场玩家资料库。</p></div>
          </section>
          <form class="mobile-form" @submit.prevent="continueNewProfile">
            <label><span>玩家昵称</span><input v-model="newProfileDraft.nickname" maxlength="18" autocomplete="off" placeholder="输入现场容易辨认的昵称" /></label>
            <fieldset><legend>性别</legend><div class="mobile-choice-row is-three">
              <button v-for="value in ['MALE', 'FEMALE', 'UNDISCLOSED']" :key="value" type="button"
                :class="{ active: newProfileDraft.gender === value }" @click="newProfileDraft.gender = value">{{ genderLabel(value) }}</button>
            </div></fieldset>
            <label><span>QQ 号</span><input v-model="newProfileDraft.qq_number" inputmode="numeric" maxlength="12" autocomplete="off" placeholder="5 至 12 位数字" @input="newProfileDraft.qq_number = newProfileDraft.qq_number.replace(/\D/g, '').slice(0, 12)" /><small>QQ 用于现场联系、Bot 识别和排队通知。</small></label>
            <fieldset><legend>默认游玩偏好</legend><div class="mobile-choice-row is-three">
              <button v-for="value in ['OPEN_TO_JOIN', 'SOLO', 'ASK_EVERY_TIME']" :key="value" type="button"
                :class="{ active: newProfileDraft.default_preference === value }" @click="newProfileDraft.default_preference = value">{{ preferenceLabel(value) }}</button>
            </div></fieldset>
            <MobileProfileSettings :settings="newProfileDraft" :bot-qq="botQq"
              @change="updateDraft(newProfileDraft, $event)" />
            <p v-if="errorDetail" class="mobile-form-error" role="alert">{{ errorDetail }}</p>
            <button class="mobile-primary" type="submit" :disabled="!newProfileValid">继续核对</button>
          </form>
        </template>

        <template v-else-if="step === 'COMPLETE'">
          <section class="mobile-step-heading">
            <button type="button" aria-label="返回选择玩家资料" @click="step = 'SELECT'"><ArrowLeft :size="19" /></button>
            <div><span>补全资料</span><h2>{{ selectedProfile.nickname }}</h2><p>确认新增设置后，才能继续使用这份资料。</p></div>
          </section>
          <form class="mobile-form" @submit.prevent="continueProfileCompletion">
            <label v-if="!selectedProfile.qqPresent"><span>QQ 号</span><input v-model="completionDraft.qq_number" inputmode="numeric" maxlength="12" autocomplete="off" placeholder="5 至 12 位数字" @input="completionDraft.qq_number = completionDraft.qq_number.replace(/\D/g, '').slice(0, 12)" /></label>
            <p v-else class="mobile-information"><Check :size="17" aria-hidden="true" />原有 QQ 会继续保留，无需重新填写。</p>
            <MobileProfileSettings :settings="completionDraft" :bot-qq="botQq"
              @change="updateDraft(completionDraft, $event)" />
            <p v-if="errorDetail" class="mobile-form-error" role="alert">{{ errorDetail }}</p>
            <button class="mobile-primary" type="submit" :disabled="!completionValid">继续核对</button>
          </form>
        </template>

        <template v-else-if="step === 'CONFIRM'">
          <section class="mobile-step-heading">
            <button type="button" aria-label="返回修改" @click="returnFromConfirmation"><ArrowLeft :size="19" /></button>
            <div><span>信息核对</span><h2>确认本次登记</h2><p>提交后，现场终端会进行最终校验。</p></div>
          </section>
          <section class="mobile-confirmation">
            <dl>
              <div><dt>玩家昵称</dt><dd>{{ confirmationProfile.nickname }} {{ genderSymbol(confirmationProfile.gender) }}</dd></div>
              <div><dt>排队机台</dt><dd>{{ session.machine_name }}</dd></div>
              <div><dt>默认游玩偏好</dt><dd>{{ preferenceLabel(confirmationProfile.defaultPreference) }}</dd></div>
              <div v-if="draftMode === 'NEW'"><dt>QQ</dt><dd>{{ confirmationProfile.qqNumber }}</dd></div>
            </dl>
            <fieldset v-if="needsCurrentPreference">
              <legend>选择本次游玩偏好</legend>
              <div class="mobile-preference-cards">
                <button type="button" :class="{ active: selectedPreference === 'OPEN_TO_JOIN' }" @click="selectedPreference = 'OPEN_TO_JOIN'"><strong>允许他人加入</strong><span>接受系统分配的共同游玩</span></button>
                <button type="button" :class="{ active: selectedPreference === 'SOLO' }" @click="selectedPreference = 'SOLO'"><strong>单人游玩</strong><span>独自占用一个等待位置</span></button>
              </div>
            </fieldset>
            <p v-if="singlePlayerMachine" class="mobile-information">
              <Smartphone :size="17" aria-hidden="true" />
              该机台仅能容纳一人游玩，本次将使用“单人游玩”。玩家资料中的默认游玩偏好不会改变。
            </p>
            <p class="mobile-information"><Smartphone :size="17" aria-hidden="true" />这是在现场扫码建立的普通登记，成功后无需再签到。</p>
            <p v-if="errorDetail" class="mobile-form-error" role="alert">{{ errorDetail }}</p>
            <button class="mobile-primary" type="button" :disabled="needsCurrentPreference && !selectedPreference" @click="submitRegistration">确认并加入排队</button>
          </section>
        </template>

        <section v-else-if="step === 'PENDING'" class="mobile-registration-result" aria-live="polite">
          <span class="is-progress" aria-hidden="true"><Clock3 :size="25" /></span>
          <h2>正在等待现场终端确认</h2>
          <p>请保持页面打开。终端会再次检查机台状态、资料版本和重复登记，完成后这里会显示最终结果。</p>
        </section>

        <section v-else-if="step === 'SUBMIT_ERROR'" class="mobile-registration-result is-error" aria-live="assertive">
          <span aria-hidden="true"><TriangleAlert :size="25" /></span>
          <h2>暂时无法确认提交结果</h2>
          <p>{{ resultDetail }}</p>
          <p class="mobile-information"><RefreshCw :size="17" aria-hidden="true" />使用同一次请求重新提交，不会建立重复登记。</p>
          <button class="mobile-primary" type="button" @click="submitRegistration">重新提交</button>
          <button class="mobile-secondary" type="button" @click="leaveFlow">返回队列</button>
        </section>

        <section v-else-if="step === 'RESULT_ERROR'" class="mobile-registration-result is-error" aria-live="assertive">
          <span aria-hidden="true"><TriangleAlert :size="25" /></span>
          <h2>暂时无法读取处理结果</h2>
          <p>{{ resultDetail }}</p>
          <button class="mobile-primary" type="button" @click="retryResult">重新读取处理结果</button>
          <button class="mobile-secondary" type="button" @click="leaveFlow">返回队列</button>
        </section>

        <section v-else-if="step === 'SUCCESS'" class="mobile-registration-result is-success" aria-live="polite">
          <span aria-hidden="true"><CircleCheck :size="26" /></span>
          <h2>已加入排队</h2>
          <p>{{ resultDetail }}</p>
          <p class="mobile-information"><Smartphone :size="17" aria-hidden="true" />这是现场普通登记，无需在终端再次签到。</p>
          <button class="mobile-primary" type="button" @click="leaveFlow">查看排队状态</button>
        </section>

        <section v-else-if="step === 'REJECTED'" class="mobile-registration-result is-error" aria-live="assertive">
          <span aria-hidden="true"><TriangleAlert :size="25" /></span>
          <h2>这次登记没有执行</h2>
          <p>{{ resultDetail }}</p>
          <p class="mobile-information"><Smartphone :size="17" aria-hidden="true" />本次二维码已经结束。如需重新登记，请在现场终端重新打开“使用移动设备登记”。</p>
          <button class="mobile-primary" type="button" @click="leaveFlow">返回队列</button>
        </section>
      </main>
    </div>
  </Teleport>
</template>

<style scoped>
.mobile-registration-shell {
  --mobile-bg: #f5f5f7;
  --mobile-card: #fff;
  --mobile-text: #1d1d1f;
  --mobile-secondary: #6e6e73;
  --mobile-tertiary: #8e8e93;
  --mobile-separator: #d2d2d7;
  --mobile-blue: #007aff;
  --mobile-soft-blue: #eaf3ff;
  --mobile-green: #248a3d;
  --mobile-soft-green: #edf8ef;
  --mobile-red: #c9342c;
  position: fixed;
  z-index: 10000;
  inset: 0;
  overflow-y: auto;
  color: var(--mobile-text);
  background: var(--mobile-bg);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}
.mobile-registration-page { width: min(100%, 880px); min-height: 100%; margin: 0 auto; padding: 22px 24px 48px; }
.mobile-registration-header { display: flex; min-height: 62px; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--mobile-separator); }
.mobile-registration-brand { display: flex; min-width: 0; align-items: center; gap: 11px; }
.mobile-registration-brand > span { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 50%; color: var(--mobile-blue); background: var(--mobile-soft-blue); line-height: 0; }
.mobile-registration-brand h1, .mobile-registration-brand p { margin: 0; }
.mobile-registration-brand h1 { font-size: 19px; font-weight: 650; line-height: 1.35; }
.mobile-registration-brand p { margin-top: 2px; color: var(--mobile-secondary); font-size: 12px; }
.mobile-registration-header > button, .mobile-step-heading > button { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border: 0; border-radius: 50%; color: var(--mobile-secondary); background: transparent; cursor: pointer; }
.mobile-registration-header > button:hover, .mobile-step-heading > button:hover { background: rgba(118, 118, 128, .09); }
.mobile-registration-intro { display: flex; margin-top: 25px; align-items: end; justify-content: space-between; gap: 18px; }
.mobile-registration-intro span, .mobile-step-heading span { color: var(--mobile-blue); font-size: 12px; font-weight: 650; }
.mobile-registration-intro h2, .mobile-registration-intro p, .mobile-step-heading h2, .mobile-step-heading p { margin: 0; }
.mobile-registration-intro h2, .mobile-step-heading h2 { margin-top: 3px; font-size: 24px; font-weight: 650; line-height: 1.3; }
.mobile-registration-intro p, .mobile-step-heading p { max-width: 48em; margin-top: 5px; color: var(--mobile-secondary); font-size: 13px; line-height: 1.6; }
.mobile-registration-intro > button { display: flex; min-height: 40px; padding: 0 14px; align-items: center; gap: 7px; border: 0; border-radius: 8px; color: #fff; background: var(--mobile-blue); cursor: pointer; font-size: 13px; font-weight: 620; white-space: nowrap; }
.mobile-search { display: grid; height: 48px; margin-top: 19px; padding: 0 13px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 9px; border: 1px solid var(--mobile-separator); border-radius: 9px; background: var(--mobile-card); }
.mobile-search > svg { color: var(--mobile-tertiary); }
.mobile-search input { min-width: 0; height: 100%; border: 0; outline: 0; color: var(--mobile-text); background: transparent; font: inherit; font-size: 14px; }
.mobile-profile-grid { display: grid; margin-top: 14px; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.mobile-profile-grid > button { position: relative; display: flex; min-width: 0; min-height: 104px; padding: 14px; align-items: flex-start; justify-content: center; flex-direction: column; border: 1px solid var(--mobile-separator); border-radius: 8px; color: var(--mobile-text); background: var(--mobile-card); text-align: left; cursor: pointer; }
.mobile-profile-grid > button:hover { border-color: color-mix(in srgb, var(--mobile-blue) 48%, var(--mobile-separator)); background: color-mix(in srgb, var(--mobile-soft-blue) 36%, var(--mobile-card)); }
.mobile-profile-grid > button.is-remembered { border-color: color-mix(in srgb, var(--mobile-blue) 42%, var(--mobile-separator)); }
.mobile-profile-grid strong { max-width: 100%; overflow: hidden; font-size: 16px; font-weight: 630; text-overflow: ellipsis; white-space: nowrap; }
.mobile-profile-grid strong em { margin-left: 4px; color: var(--mobile-tertiary); font-style: normal; }
.mobile-profile-grid strong em.is-male { color: #1677d2; }
.mobile-profile-grid strong em.is-female { color: #d7558b; }
.mobile-profile-grid small { max-width: 100%; margin-top: 4px; overflow: hidden; color: var(--mobile-secondary); font-size: 12px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.mobile-profile-remembered { position: absolute; top: 9px; right: 10px; color: var(--mobile-blue); font-size: 11px; font-weight: 620; }
.mobile-profile-incomplete { margin-top: 7px; color: #9a5b00; font-size: 11px; font-weight: 620; }
.mobile-empty { margin-top: 14px; padding: 38px 20px; border: 1px solid var(--mobile-separator); border-radius: 8px; background: var(--mobile-card); text-align: center; }
.mobile-empty strong { font-size: 14px; }
.mobile-empty p { margin: 5px 0 0; color: var(--mobile-secondary); font-size: 13px; }
.mobile-step-heading { display: grid; margin-top: 25px; grid-template-columns: 40px minmax(0, 1fr); align-items: start; gap: 8px; }
.mobile-step-heading > button { margin-top: 4px; }
.mobile-form, .mobile-confirmation { display: grid; margin-top: 20px; gap: 16px; }
.mobile-form > label { display: grid; gap: 7px; }
.mobile-form > label > span, .mobile-form legend, .mobile-confirmation legend { color: var(--mobile-secondary); font-size: 13px; font-weight: 610; }
.mobile-form input { width: 100%; height: 46px; padding: 0 13px; border: 1px solid var(--mobile-separator); border-radius: 8px; outline: 0; color: var(--mobile-text); background: var(--mobile-card); font: inherit; font-size: 14px; }
.mobile-form input:focus { border-color: var(--mobile-blue); box-shadow: 0 0 0 3px rgba(0, 122, 255, .12); }
.mobile-form label small { color: var(--mobile-secondary); font-size: 12px; line-height: 1.5; }
.mobile-form fieldset, .mobile-confirmation fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.mobile-form legend, .mobile-confirmation legend { margin-bottom: 8px; padding: 0; }
.mobile-choice-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.mobile-choice-row.is-three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.mobile-choice-row button { min-height: 44px; padding: 8px 10px; border: 1px solid var(--mobile-separator); border-radius: 8px; color: var(--mobile-text); background: var(--mobile-card); cursor: pointer; font-size: 13px; }
.mobile-choice-row button.active { border-color: color-mix(in srgb, var(--mobile-blue) 62%, var(--mobile-separator)); color: var(--mobile-blue); background: var(--mobile-soft-blue); font-weight: 620; }
.mobile-confirmation { padding: 18px; border: 1px solid var(--mobile-separator); border-radius: 8px; background: var(--mobile-card); }
.mobile-confirmation dl { margin: 0; }
.mobile-confirmation dl > div { display: grid; min-height: 43px; padding: 8px 0; grid-template-columns: minmax(110px, auto) minmax(0, 1fr); align-items: center; gap: 14px; border-bottom: 1px solid var(--mobile-separator); }
.mobile-confirmation dt, .mobile-confirmation dd { margin: 0; font-size: 13px; line-height: 1.5; }
.mobile-confirmation dt { color: var(--mobile-tertiary); }
.mobile-confirmation dd { overflow-wrap: anywhere; text-align: right; }
.mobile-preference-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.mobile-preference-cards button { display: flex; min-height: 66px; padding: 10px 12px; justify-content: center; flex-direction: column; border: 1px solid var(--mobile-separator); border-radius: 8px; color: var(--mobile-text); background: var(--mobile-bg); text-align: left; cursor: pointer; }
.mobile-preference-cards button.active { border-color: var(--mobile-blue); background: var(--mobile-soft-blue); }
.mobile-preference-cards strong, .mobile-preference-cards span { display: block; }
.mobile-preference-cards strong { font-size: 14px; }
.mobile-preference-cards span { margin-top: 3px; color: var(--mobile-secondary); font-size: 11px; }
.mobile-information { display: flex; margin: 0; padding: 11px 12px; align-items: flex-start; gap: 8px; border-left: 3px solid var(--mobile-blue); color: var(--mobile-secondary); background: var(--mobile-soft-blue); font-size: 12px; line-height: 1.6; }
.mobile-information > svg { margin-top: 1px; flex: 0 0 auto; color: var(--mobile-blue); }
.mobile-form-error { margin: 0; padding: 10px 11px; border-radius: 8px; color: var(--mobile-red); background: #fff0ef; font-size: 12px; line-height: 1.5; }
.mobile-primary, .mobile-secondary { display: flex; width: 100%; min-height: 46px; padding: 0 14px; align-items: center; justify-content: center; border: 0; border-radius: 8px; cursor: pointer; font-size: 14px; font-weight: 620; }
.mobile-primary { color: #fff; background: var(--mobile-blue); }
.mobile-primary:disabled { color: var(--mobile-tertiary); background: #e8e8ed; cursor: default; }
.mobile-secondary { color: var(--mobile-text); background: #e8e8ed; }
.mobile-registration-result { display: flex; min-height: 430px; padding: 52px 20px; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.mobile-registration-result > span { display: grid; width: 54px; height: 54px; place-items: center; border-radius: 50%; color: var(--mobile-blue); background: var(--mobile-soft-blue); }
.mobile-registration-result > span.is-progress svg, .is-spinning { animation: mobile-spin 1s linear infinite; }
.mobile-registration-result h2 { margin: 16px 0 0; font-size: 20px; font-weight: 650; }
.mobile-registration-result > p { max-width: 42em; margin: 7px 0 0; color: var(--mobile-secondary); font-size: 13px; line-height: 1.65; }
.mobile-registration-result > .mobile-information { width: min(100%, 520px); margin-top: 18px; text-align: left; }
.mobile-registration-result > button { width: min(100%, 420px); margin-top: 18px; }
.mobile-registration-result > button + button { margin-top: 8px; }
.mobile-registration-result.is-success > span { color: var(--mobile-green); background: var(--mobile-soft-green); }
.mobile-registration-result.is-error > span { color: var(--mobile-red); background: #fff0ef; }
@media (max-width: 700px) {
  .mobile-registration-page { padding: 12px 14px 36px; }
  .mobile-registration-header { min-height: 58px; }
  .mobile-registration-intro { align-items: stretch; flex-direction: column; }
  .mobile-registration-intro > button { justify-content: center; }
  .mobile-profile-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .mobile-choice-row.is-three { grid-template-columns: 1fr; }
}
@media (max-width: 390px) {
  .mobile-profile-grid { grid-template-columns: minmax(0, 1fr); }
  .mobile-preference-cards { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .mobile-registration-result > span.is-progress svg, .is-spinning { animation: none; }
}
@keyframes mobile-spin { to { transform: rotate(360deg); } }
</style>
