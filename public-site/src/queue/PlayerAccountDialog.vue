<script setup>
import { Bell, CircleCheck, LogIn, LogOut, Save, ShieldCheck, UserRound, X } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  bindingToken: { type: String, default: '' }
})
const emit = defineEmits(['close', 'bound'])

const QUEUE_API_URL = import.meta.env.VITE_QUEUE_STATUS_API_URL ||
  (typeof window !== 'undefined' ? `${window.location.origin}/api/queue-status` : '/api/queue-status')
const ACCOUNT_API_URL = import.meta.env.VITE_PLAYER_ACCOUNT_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'player-account')
const QUEUE_COMMAND_STATUS_API_URL = QUEUE_API_URL.replace(
  /queue-status\/?(?:\?.*)?$/,
  'queue-online/commands'
)

const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const account = ref(null)
const binding = ref(null)
const qq = ref('')
const password = ref('')
const passwordConfirmation = ref('')
const bindingCompleted = ref(false)
const activeSection = ref('overview')
const profileDraft = ref(null)
const originalProfile = ref(null)
const profileSubmitting = ref(false)
const profileError = ref('')
const profileNotice = ref('')
const currentPassword = ref('')
const passwordForm = ref({ current: '', next: '', confirmation: '' })
const passwordSubmitting = ref(false)
const passwordError = ref('')
const passwordNotice = ref('')
const csrfToken = ref('')
const queueLoading = ref(false)
const queueError = ref('')
const queueNotice = ref('')
const queueState = ref(null)
const queueActionMode = ref(null)
const queueTargetMachineId = ref('')
const queuePreference = ref('SOLO')
const queueActionSubmitting = ref(false)
let queueCommandTimer

const title = computed(() => {
  if (props.bindingToken && !bindingCompleted.value) return '绑定网页账户'
  return account.value ? '个人账户' : '登录个人账户'
})

function cookieValue(name) {
  const prefix = `${encodeURIComponent(name)}=`
  return document.cookie.split(';').map((value) => value.trim())
    .find((value) => value.startsWith(prefix))?.slice(prefix.length) || ''
}

function csrfHeaders() {
  const token = csrfToken.value || cookieValue('maimai_q_session_csrf')
  return token ? { 'X-CSRF-Token': decodeURIComponent(token) } : {}
}

function createProfileDraft(profile) {
  if (!profile) return null
  return {
    nickname: profile.nickname || '',
    gender: profile.gender || 'UNDISCLOSED',
    default_preference: profile.default_preference || 'ASK_EVERY_TIME',
    qq_number: profile.qq_number || '',
    qq_visibility: profile.qq_visibility || 'PUBLIC_WEBSITE',
    notification_enabled: profile.notification_enabled !== false,
    notify_queue_changes: profile.notify_queue_changes !== false,
    notify_playing_position: profile.notify_playing_position === true,
    notify_online_check_in: profile.notify_online_check_in !== false,
    notify_absence: profile.notify_absence !== false,
    notify_machine_status: profile.notify_machine_status === true,
    terminal_editing_allowed: profile.terminal_editing_allowed === true,
    visited_venues_public: profile.visited_venues_public !== false
  }
}

function setAccount(nextAccount) {
  account.value = nextAccount
  const draft = createProfileDraft(nextAccount?.profile)
  profileDraft.value = draft
  originalProfile.value = draft ? { ...draft } : null
  currentPassword.value = ''
  passwordForm.value = { current: '', next: '', confirmation: '' }
}

function createRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16)
    const result = character === 'x' ? value : (value & 0x3) | 0x8
    return result.toString(16)
  })
}

async function accountRequest(path = '', options = {}) {
  const { headers = {}, ...requestOptions } = options
  const response = await fetch(`${ACCOUNT_API_URL}${path}`, {
    credentials: 'include',
    ...requestOptions,
    headers: { 'Content-Type': 'application/json', ...headers }
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const requestError = new Error(payload.error || '暂时无法连接服务端，请稍后重试。')
    requestError.code = payload.code
    throw requestError
  }
  return payload
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (props.bindingToken) {
      const response = await accountRequest(`/bindings/${encodeURIComponent(props.bindingToken)}`)
      binding.value = response
      qq.value = response.profile?.qq_number || ''
      return
    }
    const response = await accountRequest()
    csrfToken.value = response.csrf_token || ''
    setAccount(response.account)
    qq.value = response.account?.profile?.qq_number || ''
    await loadQueueState()
  } catch (loadError) {
    if (!props.bindingToken && loadError.code === 'ACCOUNT_LOGIN_REQUIRED') {
      setAccount(null)
    } else {
      error.value = loadError.message
    }
  } finally {
    loading.value = false
  }
}

async function login() {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const response = await accountRequest('/login', {
      method: 'POST',
      body: JSON.stringify({ qq: qq.value.trim(), password: password.value })
    })
    csrfToken.value = response.csrf_token || ''
    setAccount(response.account)
    password.value = ''
    await loadQueueState()
  } catch (loginError) {
    error.value = loginError.message
  } finally {
    submitting.value = false
  }
}

async function completeBinding() {
  if (submitting.value) return
  if (password.value !== passwordConfirmation.value) {
    error.value = '两次输入的密码不一致。'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const response = await accountRequest(
      `/bindings/${encodeURIComponent(props.bindingToken)}/complete`,
      { method: 'POST', body: JSON.stringify({ password: password.value }) }
    )
    csrfToken.value = response.csrf_token || ''
    setAccount(response.account)
    bindingCompleted.value = true
    password.value = ''
    passwordConfirmation.value = ''
    emit('bound')
    await loadQueueState()
  } catch (bindingError) {
    error.value = bindingError.message
  } finally {
    submitting.value = false
  }
}

async function logout() {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await accountRequest('/logout', {
      method: 'POST',
      headers: csrfHeaders()
    })
    setAccount(null)
    csrfToken.value = ''
    queueState.value = null
    password.value = ''
  } catch (logoutError) {
    error.value = logoutError.message
  } finally {
    submitting.value = false
  }
}

async function loadQueueState() {
  if (!account.value) return
  queueLoading.value = true
  queueError.value = ''
  try {
    queueState.value = await accountRequest('/queue')
  } catch (loadQueueError) {
    queueError.value = loadQueueError.message
  } finally {
    queueLoading.value = false
  }
}

function registrationAbsenceStatus(registration) {
  if (registration.deferred_once) return 'DEFER_ONE_ROUND'
  if (registration.temporarily_away) return 'TEMPORARILY_AWAY'
  return 'NONE'
}

function accountQueuePositionText(registration) {
  return registration.position === 'PLAYING'
    ? `${registration.machine_id} 机台游玩位置`
    : `队列位置 ${registration.machine_id}${registration.position_index}`
}

function accountQueueEstimateText(registration) {
  if (registration.position === 'PLAYING') return '现在可以游玩'
  if (registration.online_registration_pending_check_in) return '完成现场签到后才是有效登记'
  if (registration.temporarily_away) return '暂时离开，无法估算'
  if (registration.estimated_wait_minutes == null) return '暂时无法估算'
  if (registration.estimated_wait_minutes < 1) return '预计很快可以游玩'
  return `约 ${registration.estimated_wait_minutes} 分钟后可以游玩`
}

function accountQueueStateText(registration) {
  if (registration.online_registration_pending_check_in) return '线上登记·待签到'
  if (registration.temporarily_away) return `暂时离开·已轮空 ${registration.temporary_away_skipped_turns || 0} 次`
  if (registration.deferred_once) return '暂缓一次'
  return registration.preference === 'SOLO' ? '单人游玩' : '允许他人加入'
}

function targetMachineStableId(machineId) {
  return queueState.value?.queue?.machines?.find((machine) => machine.id === machineId)?.stable_id
}

function queueOperationPayload(registration, operation, extra = {}) {
  return {
    request_id: createRequestId(),
    operation,
    expected_queue_id: queueState.value.queue.queue_id,
    expected_registration_id: registration.registration_id,
    expected_machine_id: registration.machine_id,
    expected_position: registration.position,
    expected_fixed_pair_id: registration.fixed_pair_id || null,
    expected_absence_status: registrationAbsenceStatus(registration),
    expected_temporary_away_skipped_turns: registration.temporary_away_skipped_turns || 0,
    expected_pending_check_in: registration.online_registration_pending_check_in === true,
    expected_machine_configuration_revision: queueState.value.queue.machine_configuration_revision,
    expected_machine_stable_id: registration.machine_stable_id,
    ...extra
  }
}

function finishQueueCommand(message, errorMessage = '') {
  queueActionSubmitting.value = false
  queueActionMode.value = null
  queueNotice.value = message
  queueError.value = errorMessage
  loadQueueState()
}

async function pollQueueCommand(commandId, attempts = 0) {
  try {
    const response = await fetch(`${QUEUE_COMMAND_STATUS_API_URL}/${encodeURIComponent(commandId)}`, {
      credentials: 'include', cache: 'no-store', headers: { Accept: 'application/json' }
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.error || '暂时无法读取现场处理结果。')
    if (payload.status === 'PENDING' && attempts < 30) {
      queueCommandTimer = window.setTimeout(() => pollQueueCommand(commandId, attempts + 1), 1500)
      return
    }
    if (payload.status === 'APPLIED') {
      finishQueueCommand(payload.result_detail || '现场终端已完成这次操作。')
    } else if (payload.status === 'PENDING') {
      finishQueueCommand('操作仍在等待现场终端处理，请稍后刷新查看。')
    } else {
      finishQueueCommand('', payload.result_detail || '现场终端没有执行这次操作。')
    }
  } catch (pollError) {
    if (attempts < 30) {
      queueCommandTimer = window.setTimeout(() => pollQueueCommand(commandId, attempts + 1), 1500)
    } else {
      finishQueueCommand('', pollError.message)
    }
  }
}

async function submitQueueAction(registration, operation, extra = {}) {
  if (queueActionSubmitting.value || !queueState.value?.queue?.remote_actions) return
  queueActionSubmitting.value = true
  queueError.value = ''
  queueNotice.value = '操作已提交，正在等待现场终端确认。'
  try {
    const response = await accountRequest('/queue-commands', {
      method: 'POST',
      headers: csrfHeaders(),
      body: JSON.stringify(queueOperationPayload(registration, operation, extra))
    })
    if (response.status === 'APPLIED' || response.status === 'REJECTED') {
      await pollQueueCommand(response.command_id)
    } else {
      pollQueueCommand(response.command_id)
    }
  } catch (actionError) {
    finishQueueCommand('', actionError.message)
  }
}

function profileChanged() {
  if (!profileDraft.value || !originalProfile.value) return false
  return Object.keys(originalProfile.value).some((key) => (
    profileDraft.value[key] !== originalProfile.value[key]
  ))
}

function hasUnsavedAccountForm() {
  if (profileChanged()) return true
  if (binding.value && !bindingCompleted.value) {
    return Boolean(password.value || passwordConfirmation.value)
  }
  return Boolean(
    currentPassword.value ||
    passwordForm.value.current ||
    passwordForm.value.next ||
    passwordForm.value.confirmation
  )
}

function requestClose() {
  if (
    hasUnsavedAccountForm() &&
    !window.confirm('当前页面有未保存的内容，确定关闭吗？')
  ) return
  emit('close')
}

function handleKeydown(event) {
  if (event.key === 'Escape') requestClose()
}

async function saveProfile() {
  if (profileSubmitting.value || !profileDraft.value || !account.value?.profile) return
  profileSubmitting.value = true
  profileError.value = ''
  profileNotice.value = ''
  const changedFields = {}
  Object.keys(originalProfile.value || {}).forEach((key) => {
    if (profileDraft.value[key] !== originalProfile.value[key]) {
      changedFields[key] = profileDraft.value[key]
    }
  })
  if (Object.prototype.hasOwnProperty.call(changedFields, 'qq_number')) {
    if (!currentPassword.value) {
      profileError.value = '修改 QQ 前需要输入当前账户密码。'
      profileSubmitting.value = false
      return
    }
    changedFields.current_password = currentPassword.value
  }
  try {
    const response = await accountRequest('/profile', {
      method: 'PATCH',
      headers: csrfHeaders(),
      body: JSON.stringify({
        expected_profile_revision: account.value.profile.profile_revision,
        ...changedFields
      })
    })
    setAccount(response.account)
    profileNotice.value = response.changed
      ? '资料已保存，其他端会在下一次同步时更新。'
      : '没有需要保存的修改。'
  } catch (saveError) {
    profileError.value = saveError.message
    if (saveError.code === 'PLAYER_PROFILE_CHANGED') await load()
  } finally {
    profileSubmitting.value = false
  }
}

async function changePassword() {
  if (passwordSubmitting.value) return
  passwordSubmitting.value = true
  passwordError.value = ''
  passwordNotice.value = ''
  try {
    const response = await accountRequest('/password', {
      method: 'POST',
      headers: csrfHeaders(),
      body: JSON.stringify({
        current_password: passwordForm.value.current,
        new_password: passwordForm.value.next,
        new_password_confirmation: passwordForm.value.confirmation
      })
    })
    setAccount(response.account)
    passwordNotice.value = response.message || '密码已修改。'
  } catch (passwordChangeError) {
    passwordError.value = passwordChangeError.message
  } finally {
    passwordSubmitting.value = false
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  load()
})
onBeforeUnmount(() => {
  if (queueCommandTimer) window.clearTimeout(queueCommandTimer)
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="account-backdrop" @click.self="requestClose">
    <section class="account-dialog" role="dialog" aria-modal="true" :aria-label="title">
      <header>
        <div class="account-title-icon" aria-hidden="true">
          <ShieldCheck v-if="bindingToken && !bindingCompleted" :size="21" />
          <UserRound v-else :size="21" />
        </div>
        <div>
          <span>maimai Q</span>
          <h2>{{ title }}</h2>
        </div>
        <button type="button" aria-label="关闭个人账户" title="关闭" @click="requestClose">
          <X :size="20" />
        </button>
      </header>

      <div v-if="loading" class="account-loading" aria-live="polite">正在读取账户信息</div>

      <template v-else-if="bindingToken && !bindingCompleted">
        <div v-if="binding" class="account-profile-summary">
          <div>
            <strong>{{ binding.profile.nickname }}</strong>
            <span>玩家编号 {{ binding.profile.public_player_id || '等待分配' }}</span>
          </div>
          <div>
            <small>绑定 QQ</small>
            <strong>{{ binding.profile.qq_number }}</strong>
          </div>
        </div>
        <p class="account-explanation">
          请核对这是你的现场玩家资料。设置密码后，这份资料将绑定到当前网页账户；以后使用 QQ 和密码登录。
        </p>
        <form v-if="binding" @submit.prevent="completeBinding">
          <label>
            <span>设置密码</span>
            <input v-model="password" type="password" autocomplete="new-password" minlength="8" maxlength="128"
              placeholder="至少 8 个字符" required />
          </label>
          <label>
            <span>再次输入密码</span>
            <input v-model="passwordConfirmation" type="password" autocomplete="new-password" minlength="8"
              maxlength="128" placeholder="再次输入密码" required />
          </label>
          <p v-if="error" class="account-error" role="alert">{{ error }}</p>
          <button class="account-primary" type="submit" :disabled="submitting">
            <ShieldCheck :size="17" />{{ submitting ? '正在绑定' : binding.rebind ? '重新绑定并设置密码' : '确认绑定' }}
          </button>
        </form>
        <p v-else-if="error" class="account-error account-error-block" role="alert">{{ error }}</p>
      </template>

      <template v-else-if="account">
        <div class="account-success">
          <CircleCheck :size="20" aria-hidden="true" />
          <span>{{ bindingCompleted ? '网页账户已绑定' : '当前已登录' }}</span>
        </div>
        <div class="account-profile-summary">
          <div>
            <strong>{{ account.profile.nickname }}</strong>
            <span>玩家编号 {{ account.profile.public_player_id || '等待分配' }}</span>
          </div>
          <div>
            <small>QQ</small>
            <strong>{{ account.profile.qq_number }}</strong>
          </div>
        </div>
        <nav class="account-tabs" aria-label="个人账户内容">
          <button type="button" :class="{ active: activeSection === 'overview' }" @click="activeSection = 'overview'">概览</button>
          <button type="button" :class="{ active: activeSection === 'profile' }" @click="activeSection = 'profile'">资料设置</button>
          <button type="button" :class="{ active: activeSection === 'notifications' }" @click="activeSection = 'notifications'">通知与隐私</button>
          <button type="button" :class="{ active: activeSection === 'security' }" @click="activeSection = 'security'">账户安全</button>
        </nav>

        <section v-if="activeSection === 'overview'" class="account-section">
          <dl class="account-details">
            <div><dt>机厅</dt><dd>{{ account.venue.name || '尚未填写机厅名称' }}</dd></div>
            <div><dt>默认游玩偏好</dt><dd>{{ account.profile.default_preference === 'SOLO' ? '单人游玩' : account.profile.default_preference === 'OPEN_TO_JOIN' ? '允许他人加入' : '每次询问' }}</dd></div>
            <div><dt>通知总开关</dt><dd>{{ account.profile.notification_enabled ? '已开启' : '已关闭' }}</dd></div>
            <div><dt>终端编辑</dt><dd>{{ account.profile.terminal_editing_allowed ? '允许' : '仅网页' }}</dd></div>
          </dl>
          <p class="account-explanation">网页账户绑定的是当前机厅的玩家资料。跨端同步可能需要终端下一次连接服务端后才会完成。</p>
          <div class="account-queue-heading">
            <div>
              <strong>我的排队</strong>
              <span>操作由现场终端按最新状态确认</span>
            </div>
            <button type="button" :disabled="queueLoading || queueActionSubmitting" @click="loadQueueState">刷新</button>
          </div>
          <p v-if="queueLoading" class="account-queue-empty">正在读取排队状态</p>
          <p v-else-if="queueState?.registrations?.length === 0" class="account-queue-empty">当前没有你的登记。</p>
          <p v-else-if="queueState?.registrations?.length > 1" class="account-error account-error-block">当前账户关联到多份登记，为避免操作错误，网页已暂停远程操作。请在现场终端处理。</p>
          <article v-else-if="queueState?.registrations?.length === 1" class="account-queue-card">
            <template v-for="registration in queueState.registrations" :key="registration.registration_id">
              <div class="account-queue-summary">
                <div>
                  <strong>{{ accountQueuePositionText(registration) }}</strong>
                  <span>{{ registration.machine_name }}</span>
                </div>
                <div>
                  <strong>{{ accountQueueEstimateText(registration) }}</strong>
                  <span>{{ accountQueueStateText(registration) }}</span>
                </div>
              </div>
              <p v-if="registration.online_registration_pending_check_in" class="account-queue-warning">请在创建登记后的 30 分钟内到现场终端完成签到；轮到进入游玩位置时仍未签到，登记也会自动退出排队。</p>
              <p v-if="!queueState.queue?.terminal_online" class="account-queue-warning">现场终端暂时离线，当前状态可能已经变化，暂不能远程操作。</p>
              <p v-else-if="!queueState.queue?.remote_actions" class="account-queue-warning">现场未开启网站远程操作，当前只能查看状态。</p>

              <div v-if="queueActionMode === 'transfer'" class="account-inline-action">
                <strong>选择要切换到的机台</strong>
                <div class="account-choice-grid">
                  <button v-for="machine in queueState.queue.machines.filter((item) => item.id !== registration.machine_id)"
                    :key="machine.id" type="button" :disabled="!machine.available"
                    :class="{ active: queueTargetMachineId === machine.id }" @click="queueTargetMachineId = machine.id">
                    {{ machine.name }}
                  </button>
                </div>
                <div class="account-confirm-actions">
                  <button type="button" @click="queueActionMode = null">取消</button>
                  <button class="primary" type="button" :disabled="!queueTargetMachineId || queueActionSubmitting"
                    @click="submitQueueAction(registration, 'TRANSFER_MACHINE', { target_machine_id: queueTargetMachineId, expected_target_machine_stable_id: targetMachineStableId(queueTargetMachineId) })">
                    确认切换机台
                  </button>
                </div>
              </div>

              <div v-else-if="queueActionMode === 'preference'" class="account-inline-action">
                <strong>选择本次游玩偏好</strong>
                <div class="account-choice-grid account-choice-grid-two">
                  <button type="button" :class="{ active: queuePreference === 'SOLO' }" @click="queuePreference = 'SOLO'">单人游玩</button>
                  <button type="button" :class="{ active: queuePreference === 'OPEN_TO_JOIN' }" @click="queuePreference = 'OPEN_TO_JOIN'">允许他人加入</button>
                </div>
                <div class="account-confirm-actions">
                  <button type="button" @click="queueActionMode = null">取消</button>
                  <button class="primary" type="button" :disabled="queueActionSubmitting"
                    @click="submitQueueAction(registration, 'CHANGE_PLAY_PREFERENCE', { preference: queuePreference })">
                    确认修改游玩偏好
                  </button>
                </div>
              </div>

              <div v-else-if="queueActionMode === 'leave'" class="account-inline-action">
                <strong>确认退出排队？</strong>
                <p>退出后会失去当前队列位置，需要重新登记才能继续排队。</p>
                <div class="account-confirm-actions">
                  <button type="button" @click="queueActionMode = null">取消</button>
                  <button class="primary is-danger" type="button" :disabled="queueActionSubmitting" @click="submitQueueAction(registration, 'LEAVE_QUEUE')">确认退出排队</button>
                </div>
              </div>

              <div v-else class="account-queue-actions">
                <template v-if="!registration.online_registration_pending_check_in">
                  <button v-if="registration.deferred_once" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="submitQueueAction(registration, 'CANCEL_DEFER_ONE_ROUND')">取消暂缓一次</button>
                  <button v-else-if="queueState.queue?.queue_rules?.allow_defer_one_round" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="submitQueueAction(registration, 'DEFER_ONE_ROUND')">暂缓一次</button>
                  <button v-if="registration.temporarily_away" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="submitQueueAction(registration, 'CANCEL_TEMPORARY_LEAVE')">取消暂时离开</button>
                  <button v-else-if="queueState.queue?.queue_rules?.allow_temporary_leave" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="submitQueueAction(registration, 'TEMPORARILY_LEAVE')">暂时离开</button>
                  <button v-if="registration.position !== 'PLAYING' && queueState.queue.machines.some((machine) => machine.id !== registration.machine_id && machine.available)" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="queueTargetMachineId = ''; queueActionMode = 'transfer'">切换机台</button>
                  <button v-if="registration.machine_capacity > 1" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="queuePreference = registration.preference; queueActionMode = 'preference'">修改游玩偏好</button>
                </template>
                <button class="is-danger" type="button" :disabled="!queueState.queue?.remote_actions || queueActionSubmitting" @click="queueActionMode = 'leave'">退出排队</button>
              </div>
            </template>
          </article>
          <p v-if="queueError" class="account-error" role="alert">{{ queueError }}</p>
          <p v-if="queueNotice" class="account-notice" role="status">{{ queueNotice }}</p>
        </section>

        <form v-else-if="activeSection === 'profile' && profileDraft" class="account-settings-form" @submit.prevent="saveProfile">
          <label>
            <span>昵称</span>
            <input v-model.trim="profileDraft.nickname" type="text" maxlength="18" autocomplete="nickname" required />
          </label>
          <label>
            <span>性别</span>
            <select v-model="profileDraft.gender">
              <option value="UNDISCLOSED">不公开</option>
              <option value="MALE">男</option>
              <option value="FEMALE">女</option>
            </select>
          </label>
          <fieldset class="account-fieldset">
            <legend>默认游玩偏好</legend>
            <div class="account-choice-grid">
              <button type="button" :class="{ active: profileDraft.default_preference === 'SOLO' }" @click="profileDraft.default_preference = 'SOLO'">单人游玩</button>
              <button type="button" :class="{ active: profileDraft.default_preference === 'OPEN_TO_JOIN' }" @click="profileDraft.default_preference = 'OPEN_TO_JOIN'">允许他人加入</button>
              <button type="button" :class="{ active: profileDraft.default_preference === 'ASK_EVERY_TIME' }" @click="profileDraft.default_preference = 'ASK_EVERY_TIME'">每次询问</button>
            </div>
          </fieldset>
          <label>
            <span>QQ（只能在网页修改）</span>
            <input v-model.trim="profileDraft.qq_number" type="text" inputmode="numeric" maxlength="12" autocomplete="off" required />
          </label>
          <label v-if="profileDraft.qq_number !== originalProfile?.qq_number">
            <span>当前账户密码</span>
            <input v-model="currentPassword" type="password" autocomplete="current-password" maxlength="128" placeholder="修改 QQ 时必须填写" />
          </label>
          <label>
            <span>QQ 显示范围</span>
            <select v-model="profileDraft.qq_visibility">
              <option value="PUBLIC_WEBSITE">网站公开</option>
              <option value="TERMINAL_ONLY">仅现场终端</option>
            </select>
          </label>
          <p class="account-permission-note">QQ 是账户身份的一部分，修改后会同步更新这份玩家资料的联系方式。</p>
          <p v-if="profileError" class="account-error" role="alert">{{ profileError }}</p>
          <p v-if="profileNotice" class="account-notice" role="status">{{ profileNotice }}</p>
          <button class="account-primary" type="submit" :disabled="profileSubmitting || !profileChanged()">
            <Save :size="17" />{{ profileSubmitting ? '正在保存' : '保存资料' }}
          </button>
        </form>

        <form v-else-if="activeSection === 'notifications' && profileDraft" class="account-settings-form" @submit.prevent="saveProfile">
          <label class="account-toggle account-toggle-primary">
            <input v-model="profileDraft.notification_enabled" type="checkbox" />
            <span><strong>排队通知总开关</strong><small>关闭后不会接收任何排队提醒。</small></span>
          </label>
          <p class="account-permission-note">通知由 QQ Bot 发送；需要先添加现场配置的 Bot QQ 为好友，才能收到私信提醒。</p>
          <fieldset class="account-fieldset">
            <legend><Bell :size="15" />通知分项</legend>
            <label class="account-toggle"><input v-model="profileDraft.notify_queue_changes" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>队列状态变化</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_playing_position" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>游玩位置变化</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_online_check_in" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>线上登记与签到</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_absence" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>暂缓一次、暂时离开和未到场</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_machine_status" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>机台状态变化</span></label>
          </fieldset>
          <fieldset class="account-fieldset">
            <legend>隐私与终端权限</legend>
            <label class="account-toggle"><input v-model="profileDraft.visited_venues_public" type="checkbox" /><span><strong>公开去过的机厅</strong><small>允许网站展示你的机厅记录。</small></span></label>
            <label class="account-toggle"><input v-model="profileDraft.terminal_editing_allowed" type="checkbox" /><span><strong>允许现场终端编辑资料</strong><small>关闭后，终端只能查看资料；QQ 始终只能在网页修改。</small></span></label>
          </fieldset>
          <p v-if="profileError" class="account-error" role="alert">{{ profileError }}</p>
          <p v-if="profileNotice" class="account-notice" role="status">{{ profileNotice }}</p>
          <button class="account-primary" type="submit" :disabled="profileSubmitting || !profileChanged()">
            <Save :size="17" />{{ profileSubmitting ? '正在保存' : '保存设置' }}
          </button>
        </form>

        <form v-else-if="activeSection === 'security'" class="account-settings-form" @submit.prevent="changePassword">
          <p class="account-explanation">修改密码后，其他设备上的网页登录会立即退出；当前页面会保持登录。</p>
          <label>
            <span>当前密码</span>
            <input v-model="passwordForm.current" type="password" autocomplete="current-password" maxlength="128" required />
          </label>
          <label>
            <span>新密码</span>
            <input v-model="passwordForm.next" type="password" autocomplete="new-password" minlength="8" maxlength="128" required />
          </label>
          <label>
            <span>再次输入新密码</span>
            <input v-model="passwordForm.confirmation" type="password" autocomplete="new-password" minlength="8" maxlength="128" required />
          </label>
          <p v-if="passwordError" class="account-error" role="alert">{{ passwordError }}</p>
          <p v-if="passwordNotice" class="account-notice" role="status">{{ passwordNotice }}</p>
          <button class="account-primary" type="submit" :disabled="passwordSubmitting">
            <ShieldCheck :size="17" />{{ passwordSubmitting ? '正在修改' : '修改密码' }}
          </button>
        </form>

        <p v-if="error" class="account-error" role="alert">{{ error }}</p>
        <button class="account-secondary" type="button" :disabled="submitting || profileSubmitting" @click="logout">
          <LogOut :size="17" />{{ submitting ? '正在退出' : '退出登录' }}
        </button>
      </template>

      <form v-else @submit.prevent="login">
        <p class="account-explanation">使用已在现场绑定的 QQ 和账户密码登录。</p>
        <label>
          <span>QQ</span>
          <input v-model="qq" type="text" inputmode="numeric" autocomplete="username" maxlength="12"
            placeholder="输入 QQ 号" required />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" maxlength="128"
            placeholder="输入账户密码" required />
        </label>
        <p v-if="error" class="account-error" role="alert">{{ error }}</p>
        <button class="account-primary" type="submit" :disabled="submitting">
          <LogIn :size="17" />{{ submitting ? '正在登录' : '登录' }}
        </button>
        <p class="account-footnote">尚未绑定时，请先在现场终端打开自己的玩家资料并生成绑定二维码。</p>
      </form>
    </section>
  </div>
</template>

<style scoped>
.account-backdrop {
  position: fixed; inset: 0; z-index: 1600; display: grid; place-items: center;
  padding: 18px; background: rgb(0 0 0 / 42%); backdrop-filter: blur(8px);
}
.account-dialog {
  width: min(560px, 100%); max-height: calc(100vh - 36px); overflow: auto;
  border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 8px;
  background: var(--queue-card, #fff); color: var(--queue-text, #1d1d1f);
  box-shadow: 0 18px 54px rgb(0 0 0 / 22%); padding: 22px;
}
.account-dialog header { display: grid; grid-template-columns: 42px 1fr 44px; gap: 12px; align-items: center; }
.account-title-icon { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 50%; background: #e8f2ff; color: #007aff; }
.account-dialog header span { display: block; color: var(--queue-secondary, #6e6e73); font-size: 11px; font-weight: 650; }
.account-dialog h2 { margin: 2px 0 0; font-size: 21px; letter-spacing: 0; }
.account-dialog header > button { width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; background: transparent; color: inherit; cursor: pointer; }
.account-dialog header > button:hover { background: var(--queue-soft, #f5f5f7); }
.account-loading { padding: 42px 0 26px; color: var(--queue-secondary, #6e6e73); text-align: center; }
.account-profile-summary { display: flex; justify-content: space-between; gap: 16px; margin-top: 20px; padding: 15px; border-radius: 8px; background: var(--queue-soft, #f5f5f7); }
.account-profile-summary > div { min-width: 0; }
.account-profile-summary > div:last-child { text-align: right; }
.account-profile-summary strong, .account-profile-summary span, .account-profile-summary small { display: block; }
.account-profile-summary strong { overflow-wrap: anywhere; font-size: 15px; }
.account-profile-summary span, .account-profile-summary small { margin-top: 3px; color: var(--queue-secondary, #6e6e73); font-size: 11px; }
.account-explanation, .account-footnote { color: var(--queue-secondary, #6e6e73); font-size: 12px; line-height: 1.65; }
.account-explanation { margin: 16px 0; }
.account-footnote { margin: 12px 0 0; text-align: center; }
.account-dialog form { display: grid; gap: 13px; }
.account-dialog label span { display: block; margin-bottom: 6px; color: var(--queue-secondary, #6e6e73); font-size: 12px; font-weight: 650; }
.account-dialog input { width: 100%; height: 48px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 7px; background: var(--queue-card, #fff); color: inherit; padding: 0 13px; font-size: 15px; }
.account-primary, .account-secondary { min-height: 48px; display: flex; align-items: center; justify-content: center; gap: 8px; border-radius: 7px; padding: 0 16px; cursor: pointer; font-weight: 700; }
.account-primary { border: 0; background: #007aff; color: #fff; }
.account-secondary { width: 100%; margin-top: 17px; border: 1px solid var(--queue-separator, #d2d2d7); background: transparent; color: #007aff; }
.account-primary:disabled, .account-secondary:disabled { opacity: .55; cursor: default; }
.account-error { margin: 0; color: #c9342e; font-size: 12px; line-height: 1.55; }
.account-error-block { margin-top: 18px; padding: 12px; border-radius: 7px; background: #fff1f0; }
.account-success { display: flex; align-items: center; gap: 8px; margin-top: 18px; padding: 11px 13px; border-radius: 7px; background: #eaf8ef; color: #247a3e; font-size: 13px; font-weight: 700; }
.account-details { margin: 14px 0 0; }
.account-details div { display: flex; justify-content: space-between; gap: 18px; padding: 11px 2px; border-bottom: 1px solid var(--queue-separator, #d2d2d7); }
.account-details dt { color: var(--queue-secondary, #6e6e73); font-size: 12px; }
.account-details dd { margin: 0; font-size: 12px; font-weight: 650; text-align: right; }
.account-tabs { display: grid; margin-top: 18px; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 5px; padding: 4px; border-radius: 8px; background: var(--queue-soft, #f5f5f7); }
.account-tabs button { min-height: 38px; border: 0; border-radius: 6px; color: var(--queue-secondary, #6e6e73); background: transparent; cursor: pointer; font-size: 11px; font-weight: 650; }
.account-tabs button.active { color: var(--queue-text, #1d1d1f); background: var(--queue-card, #fff); box-shadow: 0 1px 3px rgb(0 0 0 / 8%); }
.account-section { min-height: 180px; }
.account-queue-heading { display: flex; margin-top: 20px; align-items: center; justify-content: space-between; gap: 12px; }
.account-queue-heading strong, .account-queue-heading span { display: block; }
.account-queue-heading strong { font-size: 14px; }
.account-queue-heading span { margin-top: 2px; color: var(--queue-secondary, #6e6e73); font-size: 10px; }
.account-queue-heading button { min-width: 48px; min-height: 38px; border: 0; border-radius: 6px; color: #007aff; background: var(--queue-soft, #f5f5f7); cursor: pointer; font-size: 11px; font-weight: 650; }
.account-queue-heading button:disabled { color: var(--queue-tertiary, #8e8e93); cursor: default; }
.account-queue-empty { margin: 12px 0 0; padding: 20px 12px; color: var(--queue-secondary, #6e6e73); background: var(--queue-soft, #f5f5f7); font-size: 11px; text-align: center; }
.account-queue-card { margin-top: 10px; padding: 14px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 8px; }
.account-queue-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.account-queue-summary > div:last-child { text-align: right; }
.account-queue-summary strong, .account-queue-summary span { display: block; overflow-wrap: anywhere; }
.account-queue-summary strong { font-size: 12px; }
.account-queue-summary span { margin-top: 3px; color: var(--queue-secondary, #6e6e73); font-size: 10px; }
.account-queue-warning { margin: 11px 0 0; padding: 9px 10px; border-left: 3px solid #ff9500; color: var(--queue-secondary, #6e6e73); background: #fff7e9; font-size: 10px; line-height: 1.55; }
.account-queue-actions { display: grid; margin-top: 12px; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.account-queue-actions button, .account-confirm-actions button { min-height: 42px; padding: 0 9px; border: 0; border-radius: 7px; color: var(--queue-text, #1d1d1f); background: var(--queue-soft, #f5f5f7); cursor: pointer; font-size: 10px; font-weight: 620; }
.account-queue-actions button:disabled, .account-confirm-actions button:disabled { color: var(--queue-tertiary, #8e8e93); cursor: default; }
.account-queue-actions button.is-danger, .account-confirm-actions button.is-danger { color: #c9342e; }
.account-inline-action { display: grid; margin-top: 12px; gap: 9px; padding-top: 12px; border-top: 1px solid var(--queue-separator, #d2d2d7); }
.account-inline-action > strong { font-size: 12px; }
.account-inline-action > p { margin: 0; color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.5; }
.account-confirm-actions { display: grid; grid-template-columns: .8fr 1.2fr; gap: 6px; }
.account-confirm-actions button.primary { color: #fff; background: #007aff; }
.account-confirm-actions button.primary.is-danger { background: #c9342e; }
.account-choice-grid-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.account-settings-form { display: grid; margin-top: 17px; gap: 14px; }
.account-dialog select { width: 100%; height: 48px; padding: 0 12px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 7px; color: inherit; background: var(--queue-card, #fff); font: inherit; font-size: 14px; }
.account-fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.account-fieldset legend { display: flex; align-items: center; gap: 5px; margin-bottom: 7px; padding: 0; color: var(--queue-secondary, #6e6e73); font-size: 12px; font-weight: 650; }
.account-choice-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.account-choice-grid button { min-height: 44px; padding: 0 8px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 7px; color: var(--queue-secondary, #6e6e73); background: var(--queue-card, #fff); cursor: pointer; font-size: 11px; font-weight: 600; }
.account-choice-grid button.active { border-color: #007aff; color: #007aff; background: #e8f2ff; }
.account-toggle { display: flex; min-height: 42px; padding: 9px 0; align-items: center; gap: 10px; border-bottom: 1px solid var(--queue-separator, #d2d2d7); cursor: pointer; }
.account-toggle:last-child { border-bottom: 0; }
.account-toggle input { width: 18px; height: 18px; flex: 0 0 auto; accent-color: #007aff; }
.account-toggle > span { min-width: 0; font-size: 12px; line-height: 1.45; }
.account-toggle strong, .account-toggle small { display: block; }
.account-toggle small { margin-top: 2px; color: var(--queue-secondary, #6e6e73); font-size: 10px; }
.account-toggle input:disabled + span { color: var(--queue-tertiary, #8e8e93); }
.account-toggle-primary { padding: 11px 12px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 7px; }
.account-permission-note { margin: -5px 0 0; color: var(--queue-tertiary, #8e8e93); font-size: 10px; line-height: 1.5; }
.account-notice { margin: 0; padding: 10px 11px; border-radius: 7px; color: #247a3e; background: #eaf8ef; font-size: 11px; line-height: 1.5; }
@media (max-width: 520px) {
  .account-backdrop { align-items: end; padding: 0; }
  .account-dialog { width: 100%; max-height: 92vh; border-radius: 8px 8px 0 0; padding: 18px 16px calc(18px + env(safe-area-inset-bottom)); }
  .account-choice-grid { grid-template-columns: 1fr; }
  .account-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
