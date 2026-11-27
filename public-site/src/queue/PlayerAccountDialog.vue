<script setup>
import { Bell, Camera, ChevronDown, ChevronRight, CircleCheck, LogIn, LogOut, Pencil, Save, ShieldCheck, UserRound, X } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FriendPairFlow from './FriendPairFlow.vue'
import PairedQueueTickBox from './PairedQueueTickBox.vue'
import PostponeChoiceCards from './PostponeChoiceCards.vue'
import PreferenceOptions from './PreferenceOptions.vue'
import RegistrationRenameForm from './RegistrationRenameForm.vue'
import SoloPassTickBox from './SoloPassTickBox.vue'
import { formatWaitAmount, noArrangementWaitNote, onlineMissingWaitNote } from './waitEstimateCopy.js'
import {
  soloPassStatusParts,
  terminalSupportsFixedPairPassing,
  terminalSupportsQueueOperation
} from './soloPassCopy.js'
import {
  PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE,
  pairedQueueStatusParts,
  pairedQueueTransferNotice,
  pairedQueueUncertainEstimateText
} from './pairedQueueCopy.js'
import {
  FRIEND_PAIR_SECTION_LABEL,
  POSTPONEMENT_CANCEL_OPERATIONS,
  QUEUE_ACTION_MODE_BY_OPERATION,
  REGISTRATION_RENAME_ENTRY_LABEL,
  STOPPED_MACHINE_ACTION_NOTICE,
  TRANSFER_CHOOSER_TITLE,
  TRANSFER_FIXED_PAIR_NOTE,
  TRANSFER_WAIT_LABEL,
  fixedPairPassingChanged,
  fixedPairStateText,
  friendPairCandidateRows,
  friendPairEntry,
  friendPairOffered,
  initialPassingChoices,
  initialPreferenceChoice,
  leaveConfirmation,
  onlineCheckInLimitText,
  postponeChoiceCards,
  postponeChooserHeading,
  postponeConfirmation,
  preferenceChangeCommands,
  preferenceChangeReleasesFixedPair,
  preferenceChooserText,
  queueActionUnavailableReason as sharedQueueActionUnavailableReason,
  registrationRenameOffered,
  releaseFixedPairConfirmation,
  skipOneTurnLimitNotice,
  transferChooserRows,
  transferConfirmation,
  transferEntryLabel,
  transferEntryUnavailableReason,
  transferTargetUnavailableReason
} from './queueActionAvailability.js'
import {
  QUEUE_COMMAND_SUBMITTED_NOTICE,
  queueCommandOutcome,
  queueCommandRun,
  queueCommandRunNotice,
  runQueueCommands
} from './queueCommandChain.js'

// The confirmations reached from the cards under 延后这份登记; their 返回 goes back to the cards.
const POSTPONE_CONFIRMATION_MODES = ['defer', 'skip_one_turn', 'temporary_leave']

const props = defineProps({
  bindingToken: { type: String, default: '' },
  focusRegistrationId: { type: String, default: '' },
  // The status page's `capabilities`: which operations the terminal has declared. The account's
  // own queue API does not report them.
  capabilities: { type: Object, default: () => ({}) },
  // From the status page's public queue, for each registration that lets others pass over it:
  // whether it is about to be passed over or is being passed over now (soloPassCopy.js). The
  // account's own queue API lists only the player's registrations, so it cannot tell.
  soloPassStates: { type: Object, default: () => ({}) },
  // From the status page, for each registration it shows: the machine, whether it plays, the
  // position as the page names it (位置 B2), that position's estimate and where the other machine
  // of a pair shows its preview. With a preview the page numbers the waiting positions
  // including it, and the dialog names positions and times as the page does (website review W4).
  // From the public queue it also takes what the account's own queue API does not report: the
  // name of the other registration of a fixed pair (fixedPartnerName) and whether the check-in
  // time limit of a pending online registration has started again (checkInRestarted).
  queuePagePlaces: { type: Object, default: () => ({}) },
  // The number of waiting positions of each machine by machine id, as the status page counts
  // them; the account's own queue API lists registrations only.
  waitingPositionCounts: { type: Object, default: () => ({}) },
  // Every registration of the public queue as the status page shows it, for 与朋友共同游玩 (the
  // other registrations that wait at the same machine) and 修改登记昵称 (the nicknames in use);
  // see `pageRegistrations` in queueActionAvailability.js.
  queuePageRegistrations: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'bound', 'session', 'queue-state'])

const QUEUE_API_URL = import.meta.env.VITE_QUEUE_STATUS_API_URL ||
  (typeof window !== 'undefined' ? `${window.location.origin}/api/queue-status` : '/api/queue-status')
const ACCOUNT_API_URL = import.meta.env.VITE_PLAYER_ACCOUNT_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'player-account')
const QUEUE_COMMAND_STATUS_API_URL = import.meta.env.VITE_QUEUE_ONLINE_COMMAND_API_BASE ||
  QUEUE_API_URL.replace(
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
const loginStep = ref('QQ')
const forgotPasswordVisible = ref(false)
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
const avatarInput = ref(null)
const avatarSubmitting = ref(false)
const avatarError = ref('')
const avatarNotice = ref('')
const avatarLoadFailed = ref(false)
const csrfToken = ref('')
const queueLoading = ref(false)
const queueError = ref('')
const queueNotice = ref('')
const queueState = ref(null)
const queueActionMode = ref(null)
const queueTargetMachineId = ref('')
const queuePreference = ref('SOLO')
// The tick box in the preference panel: whether the system may arrange the registration
// between its machine and the machine paired with it.
const queuePairedTick = ref(false)
// The tick box inside 单人游玩: whether registrations behind may pass over this one.
const queuePassing = ref(false)
// The same choice of a fixed pair, shown with the pair's state: one choice for both
// registrations of the pair.
const queuePairPassing = ref(false)
// The page's current time, kept up twice a minute: the line about an announced move says
// "from HH:mm on" until that moment has passed.
const currentTime = ref(Date.now())
let clockTimer = null
// Commands run on the page, not in the dialog (queueCommandChain.js): one started here goes on
// when the dialog is closed, and a dialog opened meanwhile waits for it as well.
const queueActionSubmitting = computed(() => queueCommandRun.value !== null)
// While a request without an answer is sent again, the line says so and why nothing else can be
// sent (website review W-L7).
const queueNoticeText = computed(() => (
  queueCommandRun.value?.confirming
    ? queueCommandRunNotice()
    : queueNotice.value || queueCommandRunNotice()
))

const title = computed(() => {
  if (props.bindingToken && !bindingCompleted.value) return '绑定网页账户'
  return account.value ? '玩家资料' : '登录玩家资料'
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
  avatarLoadFailed.value = false
  const draft = createProfileDraft(nextAccount?.profile)
  profileDraft.value = draft
  originalProfile.value = draft ? { ...draft } : null
  currentPassword.value = ''
  passwordForm.value = { current: '', next: '', confirmation: '' }
  emit('session', nextAccount)
}

function clearAccountSession() {
  setAccount(null)
  loginStep.value = 'QQ'
  password.value = ''
  forgotPasswordVisible.value = false
  csrfToken.value = ''
  queueState.value = null
  emit('queue-state', null)
}

async function accountRequest(path = '', options = {}) {
  const { headers = {}, ...requestOptions } = options
  const isMultipart = typeof FormData !== 'undefined' && requestOptions.body instanceof FormData
  const response = await fetch(`${ACCOUNT_API_URL}${path}`, {
    credentials: 'include',
    ...requestOptions,
    headers: { ...(isMultipart ? {} : { 'Content-Type': 'application/json' }), ...headers }
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const requestError = new Error(payload.error || '暂时无法连接服务器，请稍后重试。')
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
  if (submitting.value || loginStep.value !== 'PASSWORD') return
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

async function lookupLoginAccount() {
  if (submitting.value) return
  const normalizedQq = qq.value.trim()
  if (!/^\d{5,12}$/.test(normalizedQq)) {
    error.value = '请输入 5 至 12 位数字的 QQ 号。'
    return
  }
  submitting.value = true
  error.value = ''
  forgotPasswordVisible.value = false
  try {
    const response = await accountRequest('/login-lookup', {
      method: 'POST',
      body: JSON.stringify({ qq: normalizedQq })
    })
    if (response.bound === true) {
      loginStep.value = 'PASSWORD'
      password.value = ''
    } else {
      loginStep.value = 'UNBOUND'
    }
  } catch (lookupError) {
    error.value = lookupError.message
  } finally {
    submitting.value = false
  }
}

function resetLoginStep() {
  if (submitting.value) return
  loginStep.value = 'QQ'
  password.value = ''
  error.value = ''
  forgotPasswordVisible.value = false
}

function handleLoginQqInput() {
  if (loginStep.value !== 'QQ') resetLoginStep()
  qq.value = qq.value.replace(/\D/g, '').slice(0, 12)
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
  if (submitting.value || avatarSubmitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await accountRequest('/logout', {
      method: 'POST',
      headers: csrfHeaders()
    })
    setAccount(null)
    loginStep.value = 'QQ'
    csrfToken.value = ''
    queueState.value = null
    emit('queue-state', null)
    password.value = ''
    forgotPasswordVisible.value = false
  } catch (logoutError) {
    error.value = logoutError.message
  } finally {
    submitting.value = false
  }
}

async function loadQueueState() {
  if (!account.value) {
    queueState.value = null
    emit('queue-state', null)
    return
  }
  queueLoading.value = true
  queueError.value = ''
  try {
    queueState.value = await accountRequest('/queue')
    emit('queue-state', queueState.value)
  } catch (loadQueueError) {
    if (loadQueueError.code === 'ACCOUNT_LOGIN_REQUIRED') {
      clearAccountSession()
      queueError.value = '登录状态已失效，请重新登录。'
    } else {
      queueError.value = loadQueueError.message
    }
  } finally {
    queueLoading.value = false
  }
}

// Where the queue page shows the registration, provided it shows it at the same machine and in
// the same kind of position; the two are read separately and may differ for a moment.
function queuePagePlace(registration) {
  const place = props.queuePagePlaces?.[registration?.registration_id]
  if (!place || place.machineId !== registration.machine_id) return null
  if (place.playing !== (registration.position === 'PLAYING')) return null
  return place
}

function accountQueuePositionText(registration) {
  const place = queuePagePlace(registration)
  if (place) return place.label
  return registration.position === 'PLAYING'
    ? `游玩位置 ${registration.machine_id}`
    : `位置 ${registration.machine_id}${registration.position_index || ''}`
}

// The estimate of the position the registration waits in, as the queue page shows it. Where
// the terminal sent this registration's own wait (estimated_wait_is_own), that one is kept: the
// position shows the earlier of its two registrations, and the other one may be arranged to the
// other machine of a pair, which does not make this one play earlier (review 2026-11-27).
function accountQueueEstimate(registration) {
  const place = queuePagePlace(registration)
  if (place && !place.playing && registration.estimated_wait_is_own !== true) {
    return {
      minutes: place.estimate ?? null,
      uncertain: place.estimateUncertain === true,
      upper: place.estimateUncertain === true ? place.estimateUpper ?? null : null
    }
  }
  const uncertain = (registration.estimated_wait_uncertain ?? registration.estimatedWaitUncertain) === true
  return {
    minutes: registration.estimated_wait_minutes ?? null,
    uncertain,
    upper: uncertain ? registration.estimated_wait_upper_minutes ?? null : null
  }
}

// Why the action of this mode cannot be carried out for the registration right now; empty
// when it can.
function queueActionUnavailableReason(registration, mode) {
  return sharedQueueActionUnavailableReason({
    terminalOnline: Boolean(queueState.value?.queue?.terminal_online),
    remoteActions: Boolean(queueState.value?.queue?.remote_actions),
    registration,
    mode,
    queueOperations: props.capabilities?.queue_operations
  })
}

function requestQueueAction(registration, mode) {
  const reason = queueActionUnavailableReason(registration, mode)
  if (reason) {
    queueError.value = reason
    queueNotice.value = ''
    return false
  }
  queueError.value = ''
  queueNotice.value = ''
  queueActionMode.value = mode
  return true
}

// Cancelling 暂缓一次, 轮空一次 or 暂时离开 takes effect at once, as on the terminal.
function cancelPostponement(registration, mode) {
  if (!requestQueueAction(registration, mode)) return
  queueActionMode.value = null
  submitQueueAction(registration, POSTPONEMENT_CANCEL_OPERATIONS[mode])
}

function otherMachinesFor(registration) {
  return queueState.value?.queue?.machines?.filter((machine) => machine.id !== registration.machine_id) || []
}

// Why the entry for moving machines is grey, or an empty string.
function queueTransferUnavailableReason(registration) {
  return queueActionUnavailableReason(registration, 'transfer') ||
    transferEntryUnavailableReason(registration, otherMachinesFor(registration))
}

// With only one other machine there is nothing to choose: the confirmation opens at once, as
// on the terminal. A machine that cannot take the registration says so before (website review
// W5), as the terminal's grey entry does.
function requestQueueTransfer(registration) {
  const reason = queueTransferUnavailableReason(registration)
  if (reason) {
    queueError.value = reason
    queueNotice.value = ''
    return
  }
  if (!requestQueueAction(registration, 'transfer')) return
  const others = otherMachinesFor(registration)
  queueTargetMachineId.value = others.length === 1 ? others[0].id : ''
}

// The machines of 选择要转至的机台, each with what waits there and the wait after moving, or
// the reason why it cannot take the registration.
function queueTransferRows(registration) {
  return transferChooserRows(registration, otherMachinesFor(registration), props.waitingPositionCounts)
}

function chooseQueueTransferTarget(row) {
  if (row.reason || queueActionSubmitting.value) return
  queueTargetMachineId.value = row.id
}

// Why the chosen machine cannot take the registration, should that have changed since the
// confirmation opened; the confirmation is then grey.
function queueTransferTargetReason(registration) {
  const target = queueState.value?.queue?.machines?.find(
    (machine) => machine.id === queueTargetMachineId.value
  )
  return transferTargetUnavailableReason(registration, target)
}

function submitQueueTransfer(registration) {
  const reason = queueTransferTargetReason(registration)
  if (reason) {
    queueError.value = reason
    queueNotice.value = ''
    return
  }
  submitQueueAction(registration, 'TRANSFER_MACHINE', {
    target_machine_id: queueTargetMachineId.value,
    expected_target_machine_stable_id: targetMachineStableId(queueTargetMachineId.value)
  })
}

function requestQueuePreference(registration) {
  if (!requestQueueAction(registration, 'preference')) return
  queuePreference.value = initialPreferenceChoice(registration)
  queuePairedTick.value = registration.also_queues_at_paired_machine === true
  const passingChoices = initialPassingChoices(registration)
  queuePassing.value = passingChoices.passing
  queuePairPassing.value = passingChoices.pairPassing
}

// The tick box that lets others pass is offered inside 单人游玩 where the preference can be
// chosen and the terminal knows the choice.
function queuePassingOffered(registration) {
  return registration.machine_capacity > 1 &&
    terminalSupportsQueueOperation(props.capabilities, 'SET_ALLOWS_PASSING')
}

// A member of a fixed pair is offered the pair's tick box where the terminal lets a fixed pair
// allow passing.
function queuePairPassingOffered(registration) {
  return registration.fixed_pair === true && queuePassingOffered(registration) &&
    terminalSupportsFixedPairPassing(props.capabilities)
}

function queuePairPassingChanged(registration) {
  return fixedPairPassingChanged({
    registration,
    preference: queuePreference.value,
    pairPassing: queuePairPassingOffered(registration) ? queuePairPassing.value : null
  })
}

// A new fixed pair is asked whether it lets others pass where the terminal lets a fixed pair
// allow passing, as on the terminal's confirmation.
function queueNewPairPassingOffered(registration) {
  return queuePassingOffered(registration) && terminalSupportsFixedPairPassing(props.capabilities)
}

// 与朋友共同游玩, the row under 也可以 in 更改游玩偏好: offered where the terminal has declared the
// operation, greyed with its reason.
function queueFriendPairOffered(registration) {
  return friendPairOffered(registration, props.capabilities?.queue_operations)
}

function queueFriendPairEntry(registration) {
  return friendPairEntry(registration, props.queuePageRegistrations)
}

function requestQueueFriendPair(registration) {
  if (queueFriendPairEntry(registration).reason) return
  requestQueueAction(registration, 'friend_pair')
}

function submitQueueFriendPair(registration, { partnerRegistrationId, allowsPassing }) {
  submitQueueAction(registration, 'CREATE_FIXED_PAIR', {
    partner_registration_id: partnerRegistrationId,
    // Sent, ticked or not, wherever the pair's box was offered.
    ...(allowsPassing === null ? {} : { allows_passing: allowsPassing })
  })
}

// 修改登记昵称: the pencil beside the registration's nickname, as on the terminal's registration
// menu.
function queueRenameOffered(registration) {
  return registrationRenameOffered(registration, props.capabilities?.queue_operations)
}

function submitQueueRename(registration, displayId) {
  submitQueueAction(registration, 'RENAME_REGISTRATION', { display_id: displayId })
}

// One small line on a registration that lets others pass over it, in the place and style of the
// line about the two machines: whether that is only allowed, about to happen or happening now.
// The account's own state decides whether there is a line at all: it is read again after every
// command, while the status page reads the public queue on its own schedule.
function soloPassStatusPartsFor(registration) {
  if (registration.allows_passing !== true) return null
  return soloPassStatusParts(
    props.soloPassStates?.[registration.registration_id] || { kind: 'enabled' },
    { fixedPair: registration.fixed_pair === true }
  )
}

// The name of the machine that forms a pair with the registration's machine, if there is one.
function pairedMachineNameFor(registration) {
  const pairedMachineId = registration?.paired_machine_id
  if (!pairedMachineId) return null
  return queueState.value?.queue?.machines?.find((machine) => (
    machine.id === pairedMachineId
  ))?.name || null
}

// One small line on a registration whose tick box is on: what the system may do with it, and
// where the box can be cleared again.
// In three parts: the sentence about a running announcement is set in colour.
function pairedQueueStatusPartsFor(registration) {
  if (!registration?.also_queues_at_paired_machine) return null
  const pairedMachineName = pairedMachineNameFor(registration)
  if (!pairedMachineName) return null
  return pairedQueueStatusParts({
    pairedMachineName,
    skipsThisRound: registration.skips_paired_machine_this_round === true,
    announced: registration.paired_arrangement_announced === true,
    notBeforeMillis: registration.paired_arrangement_not_before ?? null,
    nowMillis: currentTime.value,
    // The position at which the other machine shows the preview, as the queue page names it.
    previewPosition: queuePagePlace(registration)?.previewPosition ?? null
  })
}

function queuePairedTickChanged(registration) {
  return Boolean(pairedMachineNameFor(registration)) &&
    queuePairedTick.value !== (registration.also_queues_at_paired_machine === true)
}

// On a machine for one player the panel holds nothing but the tick box. A member of a fixed
// pair opens the panel with neither preference chosen; until it chooses one, only a change of
// a tick box can be confirmed: the one for the two machines, or the pair's own.
function queuePreferenceChangeIsEmpty(registration) {
  const preferenceChosen = registration.machine_capacity > 1 && Boolean(queuePreference.value)
  return !preferenceChosen && !queuePairedTickChanged(registration) &&
    !queuePairPassingChanged(registration)
}

// One confirmation, up to three commands for the terminal: the play preference and the two tick
// boxes, each sent once the one before has been applied (preferenceChangeCommands).
function submitQueuePreferenceChange(registration) {
  const pairedMachineName = pairedMachineNameFor(registration)
  submitQueueCommands(registration, preferenceChangeCommands({
    registration,
    preference: queuePreference.value,
    pairedTick: pairedMachineName ? queuePairedTick.value : null,
    passing: queuePassingOffered(registration) ? queuePassing.value : null,
    pairPassing: queuePairPassingOffered(registration) ? queuePairPassing.value : null
  }), pairedMachineName)
}

// A member of a fixed pair that has chosen a preference releases the pair: the terminal asks a
// second question first, and so does the panel. Otherwise the change is sent at once.
function confirmQueuePreferenceChange(registration) {
  if (preferenceChangeReleasesFixedPair(registration, queuePreference.value)) {
    queueError.value = ''
    queueNotice.value = ''
    queueActionMode.value = 'release_pair'
    return
  }
  submitQueuePreferenceChange(registration)
}

// The other registration of the fixed pair by name, where the queue page shows it.
function fixedPartnerNameFor(registration) {
  if (!registration?.fixed_pair) return null
  return props.queuePagePlaces?.[registration.registration_id]?.fixedPartnerName || null
}

function releasePairPrompt(registration) {
  return releaseFixedPairConfirmation(
    registration,
    queuePreference.value,
    fixedPartnerNameFor(registration)
  )
}

// The time limit of a pending online registration, in the terminal's words, with where and how
// to check in for a player who is not at the venue.
function accountCheckInLimitText(registration) {
  return onlineCheckInLimitText({
    restarted: props.queuePagePlaces?.[registration.registration_id]?.checkInRestarted === true,
    tellsHowToCheckIn: true
  })
}

// Why the registration shows no wait estimate at the moment, or null when it shows one. A
// pending online registration shows its estimate, which counts it as checking in, as the
// terminal and the registration detail do.
function accountQueueEstimateUnavailableText(registration) {
  if (!queueState.value?.queue?.terminal_online) return '状态待更新，暂时无法确认当前安排'
  if (registration.machine_operational === false) return '机台恢复使用后重新估算'
  if (registration.position === 'PLAYING') return '现在可以游玩'
  if (registration.temporarily_away) return '暂时离开，无法估算'
  if (accountQueueEstimate(registration).minutes == null) return '暂时无法估算'
  return null
}

// Whether the estimate shown is one the terminal marked as possibly inaccurate; the card then
// adds PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE once.
function accountQueueEstimateUncertain(registration) {
  return accountQueueEstimate(registration).uncertain &&
    accountQueueEstimateUnavailableText(registration) === null
}

// The line under an uncertain wait: the wait if the system arranges nobody between the two
// machines with its condition where the terminal sent one, otherwise the general note.
function accountQueueUncertainNote(registration) {
  return noArrangementWaitNote('这份登记', accountQueueEstimate(registration).upper) ||
    PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE
}

// The earlier time for when the pending online registrations ahead do not check in.
function accountQueueOnlineMissingNote(registration) {
  if (accountQueueEstimateUnavailableText(registration) !== null) return null
  return onlineMissingWaitNote(
    '这份登记',
    registration.wait_minutes_if_online_missing ?? registration.waitMinutesIfOnlineMissing
  )
}

function accountQueueEstimateText(registration) {
  const unavailable = accountQueueEstimateUnavailableText(registration)
  if (unavailable) return unavailable
  const minutes = accountQueueEstimate(registration).minutes
  if (accountQueueEstimateUncertain(registration)) return pairedQueueUncertainEstimateText(minutes)
  if (minutes < 1) return '预计很快可以游玩'
  return `${formatWaitAmount(minutes)}后可以游玩`
}

// Near the limit of skipped turns, the same reminder as the 标记为自己 card and the terminal
// (website review W7).
function accountQueueSkipLimitNotice(registration) {
  if (!registration.skips_one_turn) return ''
  return skipOneTurnLimitNotice(registration.temporary_away_skipped_turns || 0)
}

function accountQueueStateText(registration) {
  if (registration.online_registration_pending_check_in) return '线上登记·待签到'
  if (registration.temporarily_away) {
    const skippedTurns = registration.temporary_away_skipped_turns || 0
    return skippedTurns > 0 ? `暂时离开·已轮空 ${skippedTurns} 次` : '暂时离开'
  }
  // A state set for the next turn reads with 将 until that turn has come.
  if (registration.skips_one_turn) {
    const skippedTurns = registration.temporary_away_skipped_turns || 0
    return skippedTurns > 0 ? `将轮空一次·已轮空 ${skippedTurns} 次` : '将轮空一次'
  }
  if (registration.deferred_once) return '将暂缓一次'
  // A member of a fixed pair reads as on the terminal, not as its underlying preference.
  if (registration.fixed_pair) return fixedPairStateText(fixedPartnerNameFor(registration))
  return registration.preference === 'SOLO' ? '单人游玩' : '允许他人加入'
}

function targetMachineStableId(machineId) {
  return queueState.value?.queue?.machines?.find((machine) => machine.id === machineId)?.stable_id
}

function queueActionPrompt(registration, mode) {
  const postponement = postponeConfirmation(registration, mode)
  if (postponement) return postponement
  if (mode === 'leave') return leaveConfirmation(registration)
  return null
}

function transferPrompt(registration) {
  const target = queueState.value?.queue?.machines?.find(
    (machine) => machine.id === queueTargetMachineId.value
  )
  if (!target) return null
  // The tick box is on: say what a transfer by hand means for it.
  const pairedMachineName = registration.also_queues_at_paired_machine
    ? pairedMachineNameFor(registration)
    : null
  return transferConfirmation(
    registration,
    target,
    pairedMachineName
      ? pairedQueueTransferNotice({
          displayId: registration.display_id || registration.displayId,
          sourceMachineName: registration.machine_name,
          pairedMachineName,
          destinationIsPairedMachine: target.id === registration.paired_machine_id,
          destinationMachineName: target.name
        })
      : ''
  )
}

// Every command on the registration runs through queueCommandChain.js, which carries on when
// the dialog is closed; while the dialog is open it shows the outcome (website review W3).
function submitQueueCommands(registration, commands, pairedMachineName = null) {
  if (queueActionSubmitting.value || !registration || !commands?.length) return
  const unavailableReason = queueActionUnavailableReason(
    registration,
    QUEUE_ACTION_MODE_BY_OPERATION[commands[0].operation]
  )
  if (unavailableReason) {
    queueError.value = unavailableReason
    queueNotice.value = ''
    return
  }
  queueError.value = ''
  queueNotice.value = QUEUE_COMMAND_SUBMITTED_NOTICE
  runQueueCommands({
    source: 'account-dialog',
    registration,
    state: queueState.value,
    commands,
    pairedMachineName,
    queueOperations: props.capabilities?.queue_operations,
    accountApiUrl: ACCOUNT_API_URL,
    commandApiBase: QUEUE_COMMAND_STATUS_API_URL,
    csrfHeaders
  })
}

function submitQueueAction(registration, operation, extra = {}) {
  submitQueueCommands(registration, [{ operation, extra }])
}

// The outcome of a run, whether started here or in the registration detail: what was applied
// stays beside what was not.
watch(queueCommandOutcome, (outcome) => {
  if (!outcome) return
  queueActionMode.value = null
  if (outcome.loginRequired) {
    clearAccountSession()
    queueNotice.value = ''
    queueError.value = outcome.error
    return
  }
  queueNotice.value = outcome.notice
  const refreshed = loadQueueState()
  // The refresh clears the error line when it starts, so a failed command's reason is set
  // once it has finished; otherwise the player would see no reason at all.
  queueError.value = outcome.error
  if (outcome.error) refreshed.then(() => { queueError.value = outcome.error })
})

function profileChanged() {
  if (!profileDraft.value || !originalProfile.value) return false
  return Object.keys(originalProfile.value).some((key) => (
    profileDraft.value[key] !== originalProfile.value[key]
  ))
}

function avatarInitial(profile) {
  const nickname = String(profile?.nickname || '').trim()
  return nickname ? Array.from(nickname)[0] : '?'
}

function openAvatarPicker() {
  if (avatarSubmitting.value || !account.value) return
  avatarError.value = ''
  avatarNotice.value = ''
  avatarInput.value?.click()
}

function handleAvatarLoadError() {
  avatarLoadFailed.value = true
}

async function handleAvatarInput(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !account.value?.profile) return
  avatarError.value = ''
  avatarNotice.value = ''
  if (!file.type.startsWith('image/')) {
    avatarError.value = '请选择图片文件。'
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    avatarError.value = '头像文件不能超过 8 MB。'
    return
  }
  avatarSubmitting.value = true
  try {
    const body = new FormData()
    body.append('expected_profile_revision', String(account.value.profile.profile_revision))
    body.append('avatar', file, file.name || 'avatar')
    const response = await accountRequest('/avatar', {
      method: 'POST',
      headers: csrfHeaders(),
      body
    })
    setAccount(response.account)
    avatarNotice.value = response.changed ? '头像已更新。' : '头像没有变化。'
  } catch (uploadError) {
    avatarError.value = uploadError.message
    if (uploadError.code === 'PLAYER_PROFILE_CHANGED') await load()
  } finally {
    avatarSubmitting.value = false
  }
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
  if (avatarSubmitting.value) return
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
      ? '资料已保存，现场终端会在下一次与服务端同步后更新。'
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
  clockTimer = window.setInterval(() => { currentTime.value = Date.now() }, 30000)
  load()
})
onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
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
        <button
          type="button"
          :disabled="avatarSubmitting"
          aria-label="关闭玩家资料"
          title="关闭"
          @click="requestClose"
        >
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
          请核对这是本人的现场玩家资料。设置密码后，这份资料将绑定网页账户；之后使用 QQ 和密码登录。
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
            <ShieldCheck :size="17" />{{ submitting ? '正在绑定' : '确认绑定' }}
          </button>
        </form>
        <p v-else-if="error" class="account-error account-error-block" role="alert">{{ error }}</p>
      </template>

      <template v-else-if="account">
        <div class="account-success">
          <CircleCheck :size="20" aria-hidden="true" />
          <span>{{ bindingCompleted ? '网页账户已绑定' : '当前已登录' }}</span>
        </div>
        <div class="account-avatar-editor">
          <button
            class="account-avatar-button"
            type="button"
            :disabled="avatarSubmitting"
            aria-label="修改头像"
            title="点击头像修改"
            @click="openAvatarPicker"
          >
            <img
              v-if="account.profile.avatar_url && !avatarLoadFailed"
              :src="account.profile.avatar_url"
              alt=""
              class="account-avatar-image"
              @error="handleAvatarLoadError"
            />
            <span v-else class="account-avatar-fallback">{{ avatarInitial(account.profile) }}</span>
            <span class="account-avatar-camera" aria-hidden="true"><Camera :size="15" /></span>
          </button>
          <input
            ref="avatarInput"
            class="account-avatar-input"
            type="file"
            accept="image/*"
            @change="handleAvatarInput"
          />
          <p>点击头像修改头像</p>
          <p v-if="avatarError" class="account-error" role="alert">{{ avatarError }}</p>
          <p v-else-if="avatarNotice" class="account-notice" role="status">{{ avatarNotice }}</p>
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
        <nav class="account-tabs" aria-label="玩家资料内容">
          <button type="button" :class="{ active: activeSection === 'overview' }" @click="activeSection = 'overview'">概览</button>
          <button type="button" :class="{ active: activeSection === 'profile' }" @click="activeSection = 'profile'">资料设置</button>
          <button type="button" :class="{ active: activeSection === 'notifications' }" @click="activeSection = 'notifications'">通知与隐私</button>
          <button type="button" :class="{ active: activeSection === 'security' }" @click="activeSection = 'security'">账户安全</button>
        </nav>

        <section v-if="activeSection === 'overview'" class="account-section">
          <dl class="account-details">
            <div><dt>机厅</dt><dd>{{ account.venue.name || '尚未填写机厅名称' }}</dd></div>
            <div><dt>默认游玩偏好</dt><dd>{{ account.profile.default_preference === 'SOLO' ? '单人游玩' : account.profile.default_preference === 'OPEN_TO_JOIN' ? '允许他人加入' : '每次询问' }}</dd></div>
            <div><dt>排队通知</dt><dd>{{ account.profile.notification_enabled ? '已开启' : '已关闭' }}</dd></div>
            <div><dt>终端编辑</dt><dd>{{ account.profile.terminal_editing_allowed ? '已允许' : '未允许' }}</dd></div>
          </dl>
          <p class="account-explanation">网页登录绑定的是当前机厅的玩家资料。跨端同步可能需要终端下一次连接服务端后才会完成。</p>
          <div class="account-queue-heading">
            <div>
              <strong>我的排队</strong>
              <span>操作由现场终端按最新状态确认</span>
            </div>
            <button type="button" :disabled="queueLoading || queueActionSubmitting" @click="loadQueueState">刷新</button>
          </div>
          <p v-if="queueLoading" class="account-queue-empty">正在读取排队状态</p>
          <p v-else-if="queueState?.registrations?.length === 0" class="account-queue-empty">当前没有本人的登记。</p>
          <p v-else-if="queueState?.registrations?.length > 1" class="account-error account-error-block">当前账户关联到多份登记，为避免操作错误，网页已暂停远程操作。请在现场终端处理。</p>
          <article v-else-if="queueState?.registrations?.length === 1" class="account-queue-card"
            :class="{ 'is-focused': focusRegistrationId && queueState.registrations[0].registration_id === focusRegistrationId }">
            <template v-for="registration in queueState.registrations" :key="registration.registration_id">
              <!-- The nickname this registration shows, with the terminal's pencil beside it. -->
              <div class="account-queue-name">
                <strong>{{ registration.display_id }}</strong>
                <button v-if="queueRenameOffered(registration)" type="button" :disabled="queueActionSubmitting"
                  :aria-label="REGISTRATION_RENAME_ENTRY_LABEL" :title="REGISTRATION_RENAME_ENTRY_LABEL"
                  @click="requestQueueAction(registration, 'rename')">
                  <Pencil :size="15" aria-hidden="true" />
                </button>
              </div>
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
              <p v-if="accountQueueSkipLimitNotice(registration)" class="account-queue-skip-limit">{{ accountQueueSkipLimitNotice(registration) }}</p>
              <p v-if="accountQueueEstimateUncertain(registration)" class="account-queue-estimate-note">{{ accountQueueUncertainNote(registration) }}</p>
              <p v-if="accountQueueOnlineMissingNote(registration)" class="account-queue-estimate-note">{{ accountQueueOnlineMissingNote(registration) }}</p>
              <p v-if="registration.online_registration_pending_check_in" class="account-queue-warning">{{ accountCheckInLimitText(registration) }}</p>
              <p v-if="!queueState.queue?.terminal_online" class="account-queue-warning">现场终端暂时离线，当前只能查看状态。</p>
              <p v-else-if="registration.machine_operational === false" class="account-queue-warning">{{ STOPPED_MACHINE_ACTION_NOTICE }}</p>
              <p v-else-if="!queueState.queue?.remote_actions" class="account-queue-warning">现场未开启网页端远程操作，当前只能查看状态。</p>
              <p v-if="pairedQueueStatusPartsFor(registration)" class="account-queue-paired-status">{{ pairedQueueStatusPartsFor(registration).before }}<span class="account-queue-paired-announcement">{{ pairedQueueStatusPartsFor(registration).highlighted }}</span>{{ pairedQueueStatusPartsFor(registration).after }}</p>
              <p v-if="soloPassStatusPartsFor(registration)" class="account-queue-paired-status">{{ soloPassStatusPartsFor(registration).before }}<span class="account-queue-paired-announcement">{{ soloPassStatusPartsFor(registration).highlighted }}</span>{{ soloPassStatusPartsFor(registration).after }}</p>

              <div v-if="queueActionMode === 'transfer'" class="account-inline-action">
                <!-- One row per machine, as on the terminal: what waits there and the wait after
                  moving; a machine that cannot take the registration is grey and says why. -->
                <template v-if="otherMachinesFor(registration).length > 1">
                <strong>{{ TRANSFER_CHOOSER_TITLE }}</strong>
                <div class="account-transfer-rows">
                  <button v-for="row in queueTransferRows(registration)" :key="row.id" type="button"
                    :disabled="queueActionSubmitting" :aria-disabled="row.reason ? 'true' : 'false'"
                    :class="{ active: queueTargetMachineId === row.id, 'is-unavailable': row.reason }"
                    @click="chooseQueueTransferTarget(row)">
                    <span class="account-transfer-row-main">
                      <strong>{{ row.name }}</strong>
                      <small>{{ row.description }}</small>
                    </span>
                    <span v-if="!row.reason" class="account-transfer-row-wait">
                      <small>{{ TRANSFER_WAIT_LABEL }}</small>
                      <strong>{{ row.wait }}</strong>
                    </span>
                  </button>
                </div>
                <p v-if="registration.fixed_pair && queueState.queue.machines.some((machine) => machine.id !== registration.machine_id && machine.available && machine.capacity === 1)"
                  class="account-action-note">{{ TRANSFER_FIXED_PAIR_NOTE }}</p>
                </template>
                <template v-if="transferPrompt(registration)">
                  <strong>{{ transferPrompt(registration).title }}</strong>
                  <p class="account-action-detail">{{ transferPrompt(registration).details[0] }}</p>
                  <p class="account-action-warning">{{ transferPrompt(registration).warning }}</p>
                  <p v-for="line in transferPrompt(registration).details.slice(1)" :key="line" class="account-action-detail">{{ line }}</p>
                </template>
                <p v-if="queueTransferTargetReason(registration)" class="account-action-note">{{ queueTransferTargetReason(registration) }}</p>
                <div class="account-confirm-actions">
                  <button type="button" @click="queueActionMode = null">取消</button>
                  <button class="primary" type="button" :disabled="!queueTargetMachineId || queueActionSubmitting || Boolean(queueTransferTargetReason(registration))"
                    @click="submitQueueTransfer(registration)">
                    {{ transferPrompt(registration)?.confirm || '确认转至其他机台' }}
                  </button>
                </div>
              </div>

              <div v-else-if="queueActionMode === 'preference'" class="account-inline-action">
                <strong>{{ preferenceChooserText(registration).title }}</strong>
                <p class="account-action-detail">{{ preferenceChooserText(registration).intro }}</p>
                <p v-if="preferenceChooserText(registration).fixedPairNote" class="account-action-note">{{ preferenceChooserText(registration).fixedPairNote }}</p>
                <!-- The pair's own tick box, while the pair is kept: a chosen preference releases it. -->
                <SoloPassTickBox v-if="queuePairPassingOffered(registration) && !queuePreference" v-model="queuePairPassing"
                  fixed-pair :disabled="queueActionSubmitting"
                  :explanation-shown="account.profile.passing_introduced === true"
                  :explanation-key="account.profile.profile_id || ''" />
                <PreferenceOptions v-if="registration.machine_capacity > 1" v-model="queuePreference" v-model:passing="queuePassing"
                  :passing-available="queuePassingOffered(registration)" :disabled="queueActionSubmitting"
                  :passing-explanation-shown="account.profile.passing_introduced === true"
                  :passing-explanation-key="account.profile.profile_id || ''" />
                <p v-else class="account-action-detail">{{ preferenceChooserText(registration).singleMachineNote }}</p>
                <PairedQueueTickBox v-if="pairedMachineNameFor(registration)" v-model="queuePairedTick"
                  :disabled="queueActionSubmitting"
                  :paired-machine-name="pairedMachineNameFor(registration)"
                  :fixed-pair="registration.fixed_pair === true"
                  :takes-effect-after-a-round="!registration.also_queues_at_paired_machine && registration.position !== 'PLAYING'"
                  :explanation-shown="account.profile.paired_machine_queueing_introduced === true"
                  :explanation-key="account.profile.profile_id || ''" />
                <!-- Apart from the choices above, as on the terminal: it opens its own steps. -->
                <template v-if="queueFriendPairOffered(registration)">
                  <p class="account-action-group">{{ FRIEND_PAIR_SECTION_LABEL }}</p>
                  <button type="button" class="account-friend-pair-entry"
                    :class="{ 'is-unavailable': queueFriendPairEntry(registration).reason }"
                    :aria-disabled="queueFriendPairEntry(registration).reason ? 'true' : 'false'"
                    :disabled="queueActionSubmitting" @click="requestQueueFriendPair(registration)">
                    <span>
                      <strong>{{ queueFriendPairEntry(registration).title }}</strong>
                      <small>{{ queueFriendPairEntry(registration).description }}</small>
                    </span>
                    <ChevronRight v-if="!queueFriendPairEntry(registration).reason" :size="16" aria-hidden="true" />
                  </button>
                </template>
                <div class="account-confirm-actions">
                  <button type="button" @click="queueActionMode = null">取消</button>
                  <button class="primary" type="button"
                    :disabled="queueActionSubmitting || queuePreferenceChangeIsEmpty(registration)"
                    @click="confirmQueuePreferenceChange(registration)">
                    确认更改游玩偏好
                  </button>
                </div>
              </div>

              <!-- 与朋友共同游玩: the friend's registration, then the confirmation; 返回 goes back
                to 更改游玩偏好. -->
              <div v-else-if="queueActionMode === 'friend_pair'" class="account-inline-action">
                <FriendPairFlow :rows="friendPairCandidateRows(registration, queuePageRegistrations)"
                  :disabled="queueActionSubmitting"
                  :passing-offered="queueNewPairPassingOffered(registration)"
                  :passing-explanation-shown="account.profile.passing_introduced === true"
                  :passing-explanation-key="account.profile.profile_id || ''"
                  @back="queueActionMode = 'preference'"
                  @confirm="(choice) => submitQueueFriendPair(registration, choice)" />
              </div>

              <div v-else-if="queueActionMode === 'rename'" class="account-inline-action">
                <RegistrationRenameForm :registration="registration" :page-registrations="queuePageRegistrations"
                  :disabled="queueActionSubmitting" @cancel="queueActionMode = null"
                  @save="(displayId) => submitQueueRename(registration, displayId)" />
              </div>

              <!-- The second question of a fixed-pair member that chose a preference, as on the
                terminal; 返回 goes back to 更改游玩偏好 with the choices kept. -->
              <div v-else-if="queueActionMode === 'release_pair'" class="account-inline-action">
                <strong>{{ releasePairPrompt(registration).title }}</strong>
                <p class="account-action-detail">{{ releasePairPrompt(registration).detail }}</p>
                <div class="account-confirm-actions">
                  <button type="button" @click="queueActionMode = 'preference'">返回</button>
                  <button class="primary" type="button"
                    :disabled="queueActionSubmitting || !preferenceChangeReleasesFixedPair(registration, queuePreference)"
                    @click="submitQueuePreferenceChange(registration)">
                    {{ releasePairPrompt(registration).confirm }}
                  </button>
                </div>
              </div>

              <div v-else-if="queueActionMode === 'postpone'" class="account-inline-action">
                <PostponeChoiceCards v-bind="postponeChooserHeading(registration)"
                  :cards="postponeChoiceCards(queueState.queue?.queue_rules, (mode) => queueActionUnavailableReason(registration, mode))"
                  :disabled="queueActionSubmitting" @choose="(mode) => requestQueueAction(registration, mode)" />
                <div class="account-confirm-actions is-single">
                  <button type="button" @click="queueActionMode = null">取消</button>
                </div>
              </div>

              <div v-else-if="[...POSTPONE_CONFIRMATION_MODES, 'leave'].includes(queueActionMode)"
                class="account-inline-action">
                <template v-if="queueActionPrompt(registration, queueActionMode)">
                  <strong>{{ queueActionPrompt(registration, queueActionMode).title }}</strong>
                  <p class="account-action-detail">{{ queueActionPrompt(registration, queueActionMode).detail }}</p>
                  <!-- The turns already missed, told before confirming as on the terminal. -->
                  <p v-if="queueActionPrompt(registration, queueActionMode).limitNotice" class="account-action-limit">
                    {{ queueActionPrompt(registration, queueActionMode).limitNotice }}
                  </p>
                  <p v-if="queueActionPrompt(registration, queueActionMode).note" class="account-action-note">
                    {{ queueActionPrompt(registration, queueActionMode).note }}
                  </p>
                </template>
                <div class="account-confirm-actions">
                  <button v-if="POSTPONE_CONFIRMATION_MODES.includes(queueActionMode)" type="button" @click="queueActionMode = 'postpone'">返回</button>
                  <button v-else type="button" @click="queueActionMode = null">取消</button>
                  <button
                    class="primary"
                    :class="{ 'is-danger': queueActionMode === 'leave' }"
                    type="button"
                    :disabled="queueActionSubmitting || !queueActionPrompt(registration, queueActionMode)"
                    @click="submitQueueAction(registration, queueActionPrompt(registration, queueActionMode).operation)"
                  >
                    {{ queueActionPrompt(registration, queueActionMode)?.confirm }}
                  </button>
                </div>
              </div>

              <!-- Grouped as on the terminal's registration menu (docs/ui-guide.md, 菜单的分组).
                An action that cannot be used is grey and says why under its name, as on the
                terminal; the entry for moving machines is always there outside the playing
                position. -->
              <div v-else class="account-queue-action-groups">
                <template v-if="!registration.online_registration_pending_check_in &&
                  (registration.machine_capacity > 1 || pairedMachineNameFor(registration) || registration.position !== 'PLAYING')">
                  <p class="account-action-group">游玩方式</p>
                  <div class="account-queue-actions">
                    <button v-if="registration.machine_capacity > 1 || pairedMachineNameFor(registration)" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueActionUnavailableReason(registration, 'preference') }" @click="requestQueuePreference(registration)">
                      <span>更改游玩偏好</span>
                      <small v-if="queueActionUnavailableReason(registration, 'preference')">{{ queueActionUnavailableReason(registration, 'preference') }}</small>
                    </button>
                    <button v-if="registration.position !== 'PLAYING'" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueTransferUnavailableReason(registration) }" @click="requestQueueTransfer(registration)">
                      <span>{{ transferEntryLabel(otherMachinesFor(registration)) }}</span>
                      <small v-if="queueTransferUnavailableReason(registration)">{{ queueTransferUnavailableReason(registration) }}</small>
                    </button>
                  </div>
                </template>
                <p class="account-action-group">这份登记</p>
                <div class="account-queue-actions">
                  <template v-if="!registration.online_registration_pending_check_in">
                    <button v-if="registration.deferred_once" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueActionUnavailableReason(registration, 'cancel_defer') }" @click="cancelPostponement(registration, 'cancel_defer')">
                      <span>取消暂缓一次</span>
                      <small v-if="queueActionUnavailableReason(registration, 'cancel_defer')">{{ queueActionUnavailableReason(registration, 'cancel_defer') }}</small>
                    </button>
                    <button v-else-if="registration.skips_one_turn" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueActionUnavailableReason(registration, 'cancel_skip_one_turn') }" @click="cancelPostponement(registration, 'cancel_skip_one_turn')">
                      <span>取消轮空一次</span>
                      <small v-if="queueActionUnavailableReason(registration, 'cancel_skip_one_turn')">{{ queueActionUnavailableReason(registration, 'cancel_skip_one_turn') }}</small>
                    </button>
                    <button v-else-if="registration.temporarily_away" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueActionUnavailableReason(registration, 'cancel_temporary_leave') }" @click="cancelPostponement(registration, 'cancel_temporary_leave')">
                      <span>取消暂时离开</span>
                      <small v-if="queueActionUnavailableReason(registration, 'cancel_temporary_leave')">{{ queueActionUnavailableReason(registration, 'cancel_temporary_leave') }}</small>
                    </button>
                    <button v-else-if="registration.position !== 'PLAYING'" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueActionUnavailableReason(registration, 'postpone') }" @click="requestQueueAction(registration, 'postpone')">
                      <span>延后这份登记</span>
                      <small v-if="queueActionUnavailableReason(registration, 'postpone')">{{ queueActionUnavailableReason(registration, 'postpone') }}</small>
                    </button>
                  </template>
                  <button class="is-danger" type="button" :disabled="queueActionSubmitting" :class="{ 'is-unavailable': queueActionUnavailableReason(registration, 'leave') }" @click="requestQueueAction(registration, 'leave')">
                    <span>退出排队</span>
                    <small v-if="queueActionUnavailableReason(registration, 'leave')">{{ queueActionUnavailableReason(registration, 'leave') }}</small>
                  </button>
                </div>
              </div>
            </template>
          </article>
          <p v-if="queueError" class="account-error" role="alert">{{ queueError }}</p>
          <p v-if="queueNoticeText" class="account-notice" role="status">{{ queueNoticeText }}</p>
        </section>

        <form v-else-if="activeSection === 'profile' && profileDraft" class="account-settings-form" @submit.prevent="saveProfile">
          <label>
            <span>昵称</span>
            <input v-model.trim="profileDraft.nickname" type="text" maxlength="18" autocomplete="nickname" required />
          </label>
          <label>
            <span>性别</span>
            <span class="account-select-wrap">
              <select v-model="profileDraft.gender">
                <option value="UNDISCLOSED">不愿透露</option>
                <option value="MALE">男</option>
                <option value="FEMALE">女</option>
              </select>
              <ChevronDown :size="16" aria-hidden="true" />
            </span>
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
            <span class="account-select-wrap">
              <select v-model="profileDraft.qq_visibility">
                <option value="PUBLIC_WEBSITE">允许网页端显示</option>
                <option value="TERMINAL_ONLY">仅终端显示</option>
              </select>
              <ChevronDown :size="16" aria-hidden="true" />
            </span>
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
            <span><strong>排队通知总开关</strong><small>关闭后不会接收任何排队通知。</small></span>
          </label>
          <p class="account-permission-note">通知由 QQ Bot 发送；需要添加 QQ Bot 为好友，才能接收主动私信。</p>
          <fieldset class="account-fieldset">
            <legend><Bell :size="15" />通知分项</legend>
            <label class="account-toggle"><input v-model="profileDraft.notify_queue_changes" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>队列状态变化</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_playing_position" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>游玩位置变化</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_online_check_in" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>线上登记与签到</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_absence" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>暂缓一次、暂时离开和未到场</span></label>
            <label class="account-toggle"><input v-model="profileDraft.notify_machine_status" type="checkbox" :disabled="!profileDraft.notification_enabled" /><span>机台及营业状态</span></label>
          </fieldset>
          <fieldset class="account-fieldset">
            <legend>隐私与终端权限</legend>
            <label class="account-toggle"><input v-model="profileDraft.visited_venues_public" type="checkbox" /><span><strong>公开到访过的机厅</strong><small>允许网页端展示本人的机厅记录。</small></span></label>
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
        <button
          class="account-secondary"
          type="button"
          :disabled="submitting || profileSubmitting || avatarSubmitting"
          @click="logout"
        >
          <LogOut :size="17" />{{ submitting ? '正在退出' : '退出登录' }}
        </button>
      </template>

      <form v-else @submit.prevent="loginStep === 'QQ' ? lookupLoginAccount() : login()">
        <template v-if="loginStep === 'QQ'">
          <p class="account-explanation">先输入 QQ 号，确认是否已经绑定网页玩家资料。</p>
          <label>
            <span>QQ</span>
            <input v-model="qq" type="text" inputmode="numeric" autocomplete="username" maxlength="12"
              placeholder="输入 QQ 号" required @input="handleLoginQqInput" />
          </label>
          <p v-if="error" class="account-error" role="alert">{{ error }}</p>
          <button class="account-primary" type="submit" :disabled="submitting">
            <LogIn :size="17" />{{ submitting ? '正在查询' : '继续' }}
          </button>
        </template>

        <template v-else-if="loginStep === 'PASSWORD'">
          <div class="account-login-identity">
            <div><span>登录 QQ</span><strong>{{ qq }}</strong></div>
            <button type="button" class="account-text-action" :disabled="submitting" @click="resetLoginStep">更换 QQ</button>
          </div>
          <label>
            <span>账户密码</span>
            <input v-model="password" type="password" autocomplete="current-password" maxlength="128"
              placeholder="输入账户密码" required autofocus />
          </label>
          <button type="button" class="account-text-action account-forgot-action" @click="forgotPasswordVisible = !forgotPasswordVisible">
            忘记密码？
          </button>
          <p v-if="forgotPasswordVisible" class="account-help-note">
            忘记密码时，请联系管理员核验身份后重置。网页端暂不支持仅凭 QQ 自行重置密码。
          </p>
          <p v-if="error" class="account-error" role="alert">{{ error }}</p>
          <button class="account-primary" type="submit" :disabled="submitting">
            <LogIn :size="17" />{{ submitting ? '正在登录' : '登录' }}
          </button>
        </template>

        <template v-else>
          <div class="account-unbound-note">
            <strong>这个 QQ 尚未绑定网页玩家资料</strong>
            <p>请先在现场终端打开对应的玩家资料，选择“绑定网页账户”并扫码设置密码。完成绑定后，再使用 QQ 和密码登录。</p>
          </div>
          <button class="account-secondary account-change-qq" type="button" @click="resetLoginStep">更换 QQ</button>
        </template>
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
  --queue-card: #ffffff; --queue-soft: #f5f5f7; --queue-text: #1d1d1f;
  --queue-secondary: #6e6e73; --queue-tertiary: #8e8e93; --queue-separator: #d2d2d7;
  --queue-blue: #007aff; --queue-soft-blue: #e8f2ff; --queue-red: #c9342e;
  --queue-soft-red: #fff1f0; --queue-green: #247a3e; --queue-soft-green: #eaf8ef;
  --queue-orange: #b85c00; --queue-disabled: #e8e8ed;
  box-shadow: 0 18px 54px rgb(0 0 0 / 22%); padding: 22px;
}
.account-dialog { background: var(--queue-card); color: var(--queue-text); }
:global(html.dark .account-dialog) {
  --queue-card: #1c1c1e; --queue-soft: #2c2c2e; --queue-text: #f5f5f7;
  --queue-secondary: #a1a1a6; --queue-tertiary: #8e8e93; --queue-separator: #38383a;
  --queue-blue: #0a84ff; --queue-soft-blue: #142b44; --queue-red: #ff453a;
  --queue-soft-red: #3b2020; --queue-green: #63d8a0; --queue-soft-green: #173b2a;
  --queue-orange: #ffb35c; --queue-disabled: #2c2c2e;
}
.account-dialog header { display: grid; grid-template-columns: 42px 1fr 44px; gap: 12px; align-items: center; }
.account-title-icon { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 50%; background: var(--queue-soft-blue); color: var(--queue-blue); }
.account-dialog header span { display: block; color: var(--queue-secondary, #6e6e73); font-size: 11px; font-weight: 650; }
.account-dialog h2 { margin: 2px 0 0; font-size: 21px; letter-spacing: 0; }
.account-dialog header > button { width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; background: transparent; color: inherit; cursor: pointer; }
.account-dialog header > button:hover { background: var(--queue-soft, #f5f5f7); }
.account-dialog header > button:disabled { opacity: .45; cursor: default; }
.account-loading { padding: 42px 0 26px; color: var(--queue-secondary, #6e6e73); text-align: center; }
.account-avatar-editor { display: grid; justify-items: center; gap: 6px; margin-top: 18px; }
.account-avatar-button { position: relative; width: 104px; height: 104px; display: grid; place-items: center; padding: 0; overflow: hidden; border: 1px solid var(--queue-separator); border-radius: 50%; color: #fff; background: var(--queue-blue); cursor: pointer; }
.account-avatar-button:disabled { opacity: .62; cursor: default; }
.account-avatar-image { width: 100%; height: 100%; display: block; object-fit: cover; }
.account-avatar-fallback { font-size: 42px; font-weight: 700; line-height: 1; }
.account-avatar-camera { position: absolute; right: 4px; bottom: 4px; width: 28px; height: 28px; display: grid; place-items: center; border: 2px solid var(--queue-card); border-radius: 50%; color: #fff; background: var(--queue-blue); }
.account-avatar-input { position: absolute; width: 1px; height: 1px; overflow: hidden; opacity: 0; pointer-events: none; }
.account-avatar-editor > p { margin: 0; color: var(--queue-secondary); font-size: 11px; line-height: 1.45; text-align: center; }
.account-avatar-editor > p.account-error, .account-avatar-editor > p.account-notice { width: 100%; margin-top: 3px; }
.account-profile-summary { display: flex; justify-content: space-between; gap: 16px; margin-top: 20px; padding: 15px; border-radius: 8px; background: var(--queue-soft, #f5f5f7); }
.account-profile-summary > div { min-width: 0; }
.account-profile-summary > div:last-child { text-align: right; }
.account-profile-summary strong, .account-profile-summary span, .account-profile-summary small { display: block; }
.account-profile-summary strong { overflow-wrap: anywhere; font-size: 15px; }
.account-profile-summary span, .account-profile-summary small { margin-top: 3px; color: var(--queue-secondary, #6e6e73); font-size: 11px; }
.account-explanation, .account-footnote { color: var(--queue-secondary, #6e6e73); font-size: 12px; line-height: 1.65; }
.account-explanation { margin: 16px 0; }
.account-footnote { margin: 12px 0 0; text-align: center; }
.account-login-identity { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 2px 0 1px; padding: 11px 12px; border-radius: 7px; background: var(--queue-soft, #f5f5f7); }
.account-login-identity span { display: block; color: var(--queue-secondary, #6e6e73); font-size: 10px; }
.account-login-identity strong { display: block; margin-top: 3px; font-size: 15px; letter-spacing: .02em; }
.account-text-action { min-height: 30px; border: 0; padding: 0; color: var(--queue-blue); background: transparent; cursor: pointer; font-size: 11px; font-weight: 650; }
.account-text-action:disabled { color: var(--queue-tertiary, #8e8e93); cursor: default; }
.account-forgot-action { justify-self: start; margin-top: -5px; }
.account-help-note { margin: -2px 0 0; padding: 10px 11px; border-left: 3px solid var(--queue-blue); color: var(--queue-secondary, #6e6e73); background: var(--queue-soft-blue); font-size: 10px; line-height: 1.6; }
.account-unbound-note { margin-top: 14px; padding: 13px 14px; border-radius: 7px; background: var(--queue-soft-blue); }
.account-unbound-note strong { display: block; font-size: 13px; }
.account-unbound-note p { margin: 7px 0 0; color: var(--queue-secondary, #6e6e73); font-size: 11px; line-height: 1.65; }
.account-change-qq { margin-top: 14px; }
.account-dialog form { display: grid; gap: 13px; }
.account-dialog label > span:not(.account-select-wrap) { display: block; margin-bottom: 6px; color: var(--queue-secondary); font-size: 12px; font-weight: 650; }
.account-dialog input { width: 100%; height: 48px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 7px; background: var(--queue-card, #fff); color: inherit; padding: 0 13px; font-size: 15px; }
.account-primary, .account-secondary { min-height: 48px; display: flex; align-items: center; justify-content: center; gap: 8px; border-radius: 7px; padding: 0 16px; cursor: pointer; font-weight: 700; }
.account-primary { border: 0; background: var(--queue-blue); color: #fff; }
.account-secondary { width: 100%; margin-top: 17px; border: 1px solid var(--queue-separator); background: transparent; color: var(--queue-blue); }
.account-primary:disabled, .account-secondary:disabled { opacity: .55; cursor: default; }
.account-error { margin: 0; color: var(--queue-red); font-size: 12px; line-height: 1.55; }
.account-error-block { margin-top: 18px; padding: 12px; border-radius: 7px; background: var(--queue-soft-red); }
.account-success { display: flex; align-items: center; gap: 8px; margin-top: 18px; padding: 11px 13px; border-radius: 7px; background: var(--queue-soft-green); color: var(--queue-green); font-size: 13px; font-weight: 700; }
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
.account-queue-card.is-focused { border-color: var(--queue-blue); box-shadow: 0 0 0 3px color-mix(in srgb, var(--queue-blue) 15%, transparent); }
.account-queue-name { display: flex; min-height: 30px; margin: -4px 0 8px; align-items: center; gap: 2px; }
.account-queue-name strong { min-width: 0; overflow-wrap: anywhere; font-size: 14px; font-weight: 650; line-height: 1.4; }
.account-queue-name button { display: grid; width: 34px; height: 34px; flex: none; place-items: center; padding: 0; border: 0; border-radius: 50%; color: var(--queue-blue); background: transparent; cursor: pointer; }
.account-queue-name button:hover { background: var(--queue-soft); }
.account-queue-name button:disabled { color: var(--queue-tertiary, #8e8e93); cursor: default; }
.account-queue-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.account-queue-summary > div:last-child { text-align: right; }
.account-queue-summary strong, .account-queue-summary span { display: block; overflow-wrap: anywhere; }
.account-queue-summary strong { font-size: 12px; }
.account-queue-summary span { margin-top: 3px; color: var(--queue-secondary, #6e6e73); font-size: 10px; }
.account-queue-warning { margin: 11px 0 0; padding: 9px 10px; border-left: 3px solid #ff9500; color: var(--queue-secondary, #6e6e73); background: #fff7e9; font-size: 10px; line-height: 1.55; }
.account-queue-actions { display: grid; margin-top: 12px; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.account-queue-actions button, .account-confirm-actions button { min-height: 42px; padding: 0 9px; border: 0; border-radius: 7px; color: var(--queue-text); background: var(--queue-soft); cursor: pointer; font-size: 10px; font-weight: 620; }
.account-queue-actions button:disabled, .account-confirm-actions button:disabled { color: var(--queue-tertiary, #8e8e93); cursor: default; }
.account-queue-actions button.is-unavailable { display: grid; align-content: center; gap: 3px; padding: 8px 10px; color: var(--queue-tertiary, #8e8e93); background: var(--queue-disabled); text-align: left; }
.account-queue-actions button small { display: block; color: var(--queue-tertiary, #8e8e93); font-size: 10px; font-weight: 400; line-height: 1.5; }
.account-transfer-rows { display: grid; gap: 6px; }
.account-transfer-rows button { display: flex; min-height: 52px; padding: 9px 11px; align-items: center; justify-content: space-between; gap: 12px; border: 1px solid var(--queue-separator); border-radius: 7px; color: var(--queue-text); background: var(--queue-card); cursor: pointer; text-align: left; font: inherit; }
.account-transfer-rows button.active { border-color: var(--queue-blue); background: var(--queue-soft-blue); }
.account-transfer-rows button.is-unavailable { color: var(--queue-tertiary, #8e8e93); background: var(--queue-disabled); cursor: default; }
.account-transfer-rows button:disabled { cursor: default; }
.account-transfer-rows strong, .account-transfer-rows small { display: block; }
.account-transfer-rows strong { font-size: 12px; font-weight: 620; line-height: 1.4; }
.account-transfer-rows small { color: var(--queue-secondary, #6e6e73); font-size: 10px; font-weight: 400; line-height: 1.5; }
.account-transfer-rows button.active .account-transfer-row-main strong { color: var(--queue-blue); }
.account-transfer-rows button.is-unavailable small { color: var(--queue-tertiary, #8e8e93); }
.account-transfer-row-main { display: grid; min-width: 0; gap: 2px; }
.account-transfer-row-wait { display: grid; flex: none; gap: 2px; text-align: right; }
.account-transfer-row-wait small { color: var(--queue-tertiary, #8e8e93); }
.account-queue-actions button.is-danger, .account-confirm-actions button.is-danger { color: var(--queue-red); }
.account-queue-paired-status { margin: 11px 0 0; color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.55; }
.account-queue-estimate-note { margin: 11px 0 0; color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.55; }
.account-queue-skip-limit { margin: 11px 0 0; color: var(--queue-orange); font-size: 10px; font-weight: 570; line-height: 1.55; }
.account-queue-paired-announcement { color: var(--queue-blue, #007aff); font-weight: 570; }
.account-inline-action { display: grid; margin-top: 12px; gap: 9px; padding-top: 12px; border-top: 1px solid var(--queue-separator, #d2d2d7); }
.account-inline-action > strong { font-size: 12px; }
.account-inline-action > p { margin: 0; color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.5; }
.account-inline-action > .account-action-detail { font-size: 11px; line-height: 1.65; }
.account-inline-action > .account-action-note { padding: 9px 10px; border-left: 3px solid var(--queue-blue); color: var(--queue-secondary); background: var(--queue-soft-blue); font-size: 10px; line-height: 1.6; }
.account-inline-action > .account-action-limit { color: var(--queue-orange); font-size: 11px; font-weight: 570; line-height: 1.65; }
.account-inline-action > .account-action-group { margin: 3px 0 0; font-size: 10px; line-height: 1.5; }
.account-friend-pair-entry { display: flex; min-height: 52px; padding: 9px 11px; align-items: center; justify-content: space-between; gap: 12px; border: 1px solid var(--queue-separator); border-radius: 9px; color: var(--queue-text); background: var(--queue-card); cursor: pointer; text-align: left; font: inherit; }
.account-friend-pair-entry > span { display: grid; min-width: 0; gap: 3px; }
.account-friend-pair-entry strong { font-size: 12px; font-weight: 620; line-height: 1.4; }
.account-friend-pair-entry small { color: var(--queue-secondary, #6e6e73); font-size: 10px; font-weight: 400; line-height: 1.55; }
.account-friend-pair-entry > svg { flex: none; color: var(--queue-tertiary, #8e8e93); }
.account-friend-pair-entry.is-unavailable { color: var(--queue-tertiary, #8e8e93); background: var(--queue-disabled); cursor: default; }
.account-friend-pair-entry.is-unavailable small { color: var(--queue-tertiary, #8e8e93); }
.account-friend-pair-entry:disabled { cursor: default; }
.account-confirm-actions { display: grid; grid-template-columns: .8fr 1.2fr; gap: 6px; }
.account-confirm-actions.is-single { grid-template-columns: 1fr; }
.account-inline-action > .account-action-warning { margin: 0; padding: 9px 10px; border-left: 3px solid var(--queue-red); color: var(--queue-red); background: var(--queue-soft-red); font-size: 10px; line-height: 1.6; }
.account-queue-action-groups { margin-top: 4px; }
.account-queue-action-groups .account-queue-actions { margin-top: 6px; }
.account-action-group { display: flex; align-items: center; gap: 8px; margin: 12px 0 0; color: var(--queue-secondary); font-size: 10px; font-weight: 620; }
.account-action-group::after { content: ''; flex: 1; height: 1px; background: var(--queue-separator); }
.account-confirm-actions button.primary { color: #fff; background: var(--queue-blue); }
.account-confirm-actions button.primary.is-danger { background: var(--queue-red); }
.account-choice-grid-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.account-settings-form { display: grid; margin-top: 17px; gap: 14px; }
.account-select-wrap { position: relative; display: block; margin: 0; color: var(--queue-secondary); }
.account-select-wrap select { width: 100%; height: 48px; appearance: none; padding: 0 40px 0 12px; border: 1px solid var(--queue-separator); border-radius: 7px; color: inherit; background: var(--queue-card); font: inherit; font-size: 14px; cursor: pointer; }
.account-select-wrap > svg { position: absolute; top: 50%; right: 13px; pointer-events: none; transform: translateY(-50%); }
.account-fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.account-fieldset legend { display: flex; align-items: center; gap: 5px; margin-bottom: 7px; padding: 0; color: var(--queue-secondary, #6e6e73); font-size: 12px; font-weight: 650; }
.account-choice-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.account-choice-grid button { min-height: 44px; padding: 0 8px; border: 1px solid var(--queue-separator); border-radius: 7px; color: var(--queue-secondary); background: var(--queue-card); cursor: pointer; font-size: 11px; font-weight: 600; }
.account-choice-grid button.active { border-color: var(--queue-blue); color: var(--queue-blue); background: var(--queue-soft-blue); }
.account-toggle { display: flex; min-height: 42px; padding: 9px 0; align-items: center; gap: 10px; border-bottom: 1px solid var(--queue-separator, #d2d2d7); cursor: pointer; }
.account-toggle:last-child { border-bottom: 0; }
.account-toggle input { width: 18px; height: 18px; flex: 0 0 auto; accent-color: var(--queue-blue); }
.account-toggle > span { min-width: 0; font-size: 12px; line-height: 1.45; }
.account-toggle strong, .account-toggle small { display: block; }
.account-toggle small { margin-top: 2px; color: var(--queue-secondary, #6e6e73); font-size: 10px; }
.account-toggle input:disabled + span { color: var(--queue-tertiary, #8e8e93); }
.account-toggle-primary { padding: 11px 12px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 7px; }
.account-permission-note { margin: -5px 0 0; color: var(--queue-tertiary, #8e8e93); font-size: 10px; line-height: 1.5; }
.account-notice { margin: 0; padding: 10px 11px; border-radius: 7px; color: var(--queue-green); background: var(--queue-soft-green); font-size: 11px; line-height: 1.5; }
@media (max-width: 520px) {
  .account-backdrop { align-items: end; padding: 0; }
  .account-dialog { width: 100%; max-height: 92vh; border-radius: 8px 8px 0 0; padding: 18px 16px calc(18px + env(safe-area-inset-bottom)); }
  .account-choice-grid { grid-template-columns: 1fr; }
  .account-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
