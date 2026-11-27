<script setup>
import {
  ChevronRight,
  CircleCheck,
  History,
  Info,
  MapPin,
  Pencil,
  RefreshCw,
  TriangleAlert,
  UserPlus,
  UserRound,
  UserRoundCheck,
  Users,
  WifiOff,
  X
} from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MobileRegistrationFlow from './MobileRegistrationFlow.vue'
import MobileProfileCreationFlow from './MobileProfileCreationFlow.vue'
import PlayerAccountDialog from './PlayerAccountDialog.vue'
import FriendPairFlow from './FriendPairFlow.vue'
import PairedQueueTickBox from './PairedQueueTickBox.vue'
import PostponeChoiceCards from './PostponeChoiceCards.vue'
import PreferenceOptions from './PreferenceOptions.vue'
import RegistrationRenameForm from './RegistrationRenameForm.vue'
import SoloPassTickBox from './SoloPassTickBox.vue'
import {
  PAIRED_QUEUE_PILL,
  PAIRED_QUEUE_PREVIEW_TITLE,
  PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE,
  pairedQueueClockTime,
  pairedQueuePreviewExplanation,
  pairedQueueUncertainEstimateText,
  pairedQueueAnnouncementSentence,
  pairedQueueEntryNotice,
  pairedQueueStatusParts,
  pairedQueueTransferNotice
} from './pairedQueueCopy.js'
import {
  CLOSING_WARNING_CONFIRM,
  CLOSING_WARNING_TITLE,
  NEW_REGISTRATION_WAIT_LABEL,
  NEW_REGISTRATION_WAIT_NOTE,
  closingWarningText,
  machineQueueSummary,
  newRegistrationWaitText,
  newRegistrationWaitTextWithPaired,
  formatWaitAmount,
  noArrangementWaitNote,
  onlineMissingWaitNote,
  waitExtendsPastClosing
} from './waitEstimateCopy.js'
import {
  soloPassStatesByRegistrationId,
  soloPassStatusParts,
  soloPassTileText,
  terminalSupportsFixedPairPassing,
  terminalSupportsQueueOperation
} from './soloPassCopy.js'
import { compactMiddleDots, formatMiddleDots, normalizeMachineConfiguration } from './machineConfiguration.js'
import {
  FRIEND_PAIR_SECTION_LABEL,
  ONLINE_REGISTRATION_CHECKED_IN_FACT,
  POSTPONEMENT_CANCEL_OPERATIONS,
  PREFERENCE_OPTIONS,
  PREFERENCE_RECOMMENDED_BADGE,
  QUEUE_ACTION_MODE_BY_OPERATION,
  REGISTRATION_RENAME_ENTRY_LABEL,
  SKIP_ONE_TURN_EXPLANATION,
  skipOneTurnLimitNotice,
  STOPPED_MACHINE_ACTION_NOTICE,
  TRANSFER_CHOOSER_TITLE,
  TRANSFER_FIXED_PAIR_NOTE,
  TRANSFER_WAIT_LABEL,
  fixedPairPassingChanged,
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
  queueActionUnavailableReason,
  registrationRenameOffered,
  releaseFixedPairConfirmation,
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
import {
  CONFIRMING_REGISTRATION_DETAIL,
  CONFIRMING_RESULT_TITLE,
  fetchWithTimeout,
  isConnectionLost,
  retryDelay,
  retryWhileConnectionLost,
  waitForConnection
} from './commandDelivery.js'

// The confirmations reached from the cards under 延后这份登记; their 返回 goes back to the cards.
const POSTPONE_CONFIRMATION_MODES = ['defer', 'skip_one_turn', 'temporary_leave']

// What each play preference means, in the words of 更改游玩偏好; the join step says the same.
const PREFERENCE_DESCRIPTIONS = Object.fromEntries(
  PREFERENCE_OPTIONS.map((option) => [option.value, option.description])
)

const QUEUE_API_URL = import.meta.env.VITE_QUEUE_STATUS_API_URL ||
  (typeof window !== 'undefined' ? `${window.location.origin}/api/queue-status` : '/api/queue-status')
const PLAYER_ACCOUNT_API_URL = import.meta.env.VITE_PLAYER_ACCOUNT_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'player-account')
const QUEUE_LOG_API_URL = import.meta.env.VITE_QUEUE_LOG_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'queue-logs')
const QUEUE_VERSIONS_API_URL = import.meta.env.VITE_QUEUE_VERSIONS_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'queue-versions')
const QUEUE_ONLINE_PROFILE_API_URL = import.meta.env.VITE_QUEUE_ONLINE_PROFILE_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'queue-online/profile')
const QUEUE_ONLINE_JOIN_API_URL = import.meta.env.VITE_QUEUE_ONLINE_JOIN_API_URL ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'queue-online/join')
const QUEUE_ONLINE_COMMAND_API_BASE = import.meta.env.VITE_QUEUE_ONLINE_COMMAND_API_BASE ||
  QUEUE_API_URL.replace(/queue-status\/?(?:\?.*)?$/, 'queue-online/commands')
const OPERATION_FEEDBACK_TTL_MS = 10_000
const REFRESH_INTERVAL = 10000
const SNAPSHOT_STALE_AFTER = 90000
const ONLINE_COMMAND_POLL_INTERVAL = 1500
const SELF_STORAGE_KEY = 'maimai-q:marked-registration:v1'
const MAX_SELF_REGISTRATION_HISTORY = 24
const SUPPORTED_MACHINE_IDS = [...'ABCDEFGHIJ']
const DEFAULT_MACHINE_GROUP_ID = '00000000000000000000000000000001'
const defaultMachineDefinitions = SUPPORTED_MACHINE_IDS.map((id, index) => ({
  id,
  name: index === 0
    ? '左侧·机台 A'
    : index === 1
      ? '右侧·机台 B'
      : index === 2
        ? '中间左侧·机台 C'
        : index === 3
           ? '中间右侧·机台 D'
           : `第 ${index + 1} 台·机台 ${id}`
}))
const logSourceDefinitions = [
  { value: 'ALL', label: '全部来源' },
  { value: 'ON_SITE_TERMINAL', label: '现场终端' },
  { value: 'QQ_BOT', label: 'QQ Bot' },
  { value: 'SYSTEM_AUTOMATIC', label: '系统自动' },
  { value: 'WEBSITE_REMOTE', label: '网页端' },
  { value: 'MOBILE_DEVICE', label: '移动设备' }
]

const machines = ref(defaultMachineDefinitions.slice(0, 2).map(createEmptyMachine))
const machineGroups = ref([{ id: DEFAULT_MACHINE_GROUP_ID, name: '分组 1' }])
const defaultMachineGroupId = ref(DEFAULT_MACHINE_GROUP_ID)
const activeMachineGroupId = ref(DEFAULT_MACHINE_GROUP_ID)
const registrationOpen = ref(null)
const businessHours = ref({
  enabled: false,
  outside: false,
  closingSoon: false,
  closingGrace: false,
  closesAt: null,
  registrationClosesAt: null
})
const terminal = ref(null)
const venue = ref(null)
const capabilities = ref({})
const testData = ref(false)
const capturedAt = ref(null)
const queueId = ref(null)
const machineConfigurationRevision = ref(1)
const hasSnapshot = ref(false)
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref(false)
const activeView = ref('queue')
const selectedDetail = ref(null)
const detailActionMode = ref(null)
const detailActionTargetMachineId = ref('')
const detailActionPreference = ref('SOLO')
// The tick box in the preference panel of the player's own registration.
const detailActionPairedQueue = ref(false)
// The tick box inside 单人游玩 in the same panel: whether registrations behind may pass over it.
const detailActionPassing = ref(false)
// The same choice of a fixed pair, shown with the pair's state: one choice for both
// registrations of the pair.
const detailActionPairPassing = ref(false)
// Commands run on the page (queueCommandChain.js): one goes on when the detail or the account
// dialog that started it is closed, and nothing else is sent until it ends.
const detailActionSubmitting = computed(() => queueCommandRun.value !== null)
const detailActionError = ref('')
const detailActionNotice = ref('')
const operationFeedback = ref([])
const versionDialogVisible = ref(false)
const clientVersions = ref(null)
const clientVersionsLoading = ref(false)
const clientVersionsError = ref(false)
const pendingSelfRegistration = ref(null)
const markedSelf = ref(null)
const playerAccount = ref(null)
const playerAccountQueueState = ref(null)
const accountSelfIdentity = ref(null)
const playerAccountSessionReady = ref(false)
const currentLogs = ref([])
const currentLogsQueueId = ref(null)
const currentLogsNextCursor = ref(null)
const logsLoading = ref(false)
const logsLoadingMore = ref(false)
const logsError = ref(false)
const logFilter = ref('ALL')
const logSourceFilter = ref('ALL')
const markedSelfLogs = ref([])
const selfStorageAvailable = ref(true)
const currentTime = ref(Date.now())
const onlineJoinVisible = ref(false)
const onlineJoinStep = ref('LOOKUP')
const onlineJoinAudience = ref('OTHER')
const onlineJoinQq = ref('')
const onlineJoinMachineId = ref('A')
const onlineJoinProfile = ref(null)
const onlineJoinMachines = ref([])
const onlineJoinGroups = ref([])
const onlineJoinExistingRegistration = ref(null)
const onlineJoinPreference = ref(null)
// The tick box of the registration being created, remembered with the machine and the
// profile it was ticked for: choosing another machine or profile leaves it unticked again, and
// every new join starts unticked (website review W6).
const onlineJoinPairedAnswer = ref(null)
// The tick box inside 单人游玩 of the registration being created, remembered in the same way.
// Unticked unless the player ticks it; choosing 允许他人加入 clears it.
const onlineJoinPassingAnswer = ref(null)
// 将本次选择保存为默认游玩偏好 of the registration being created, remembered in the same way; every
// new join starts unticked, as on the terminal.
// The warning that a new registration at a machine may not play before closing time, as on the
// terminal: { machineId, continuation }, where the continuation is choosing that machine or
// submitting the registration. A machine whose warning was answered with 仍然继续 does not
// warn again during this join.
const onlineJoinClosingWarning = ref(null)
const onlineJoinClosingAcknowledgedMachineIds = ref([])
const onlineJoinLoading = ref(false)
const onlineJoinError = ref('')
const onlineJoinCommandId = ref(null)
const onlineJoinQueueId = ref(null)
const onlineJoinMachineConfigurationRevision = ref(null)
const onlineJoinResultDetail = ref('')
const onlineJoinResultRegistrationId = ref(null)
const onlineJoinTerminalApplied = ref(false)
// While the online registration, or the reading of its result, gets no answer and is made again
// (website review W-L7).
const onlineJoinConfirming = ref(false)
const mobileRegistrationToken = ref('')
const mobileProfileCreationToken = ref('')
const playerAccountBindingToken = ref('')
const playerAccountDialogVisible = ref(false)
const playerAccountFocusRegistrationId = ref('')
let refreshTimer
let clockTimer
let onlineCommandTimer
// The online registration last sent, { requestId, payload }, for 重新提交 when its result is
// unknown; kept in memory only.
let onlineJoinPendingRequest = null
// Readings of the online registration's result in a row that got no answer.
let onlineJoinPollFailures = 0
const operationFeedbackTimers = new Map()

function addOperationFeedback(kind, title, detail, retry = false) {
  const item = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    kind,
    title,
    detail,
    retry
  }
  operationFeedback.value = [item, ...operationFeedback.value].slice(0, 3)
  const timer = window.setTimeout(() => {
    operationFeedbackTimers.delete(item.id)
    operationFeedback.value = operationFeedback.value.filter((entry) => entry.id !== item.id)
  }, OPERATION_FEEDBACK_TTL_MS)
  operationFeedbackTimers.set(item.id, timer)
  for (const [id, discardedTimer] of operationFeedbackTimers) {
    if (!operationFeedback.value.some((entry) => entry.id === id)) {
      window.clearTimeout(discardedTimer)
      operationFeedbackTimers.delete(id)
    }
  }
}

function dismissOperationFeedback(id) {
  const timer = operationFeedbackTimers.get(id)
  if (timer) window.clearTimeout(timer)
  operationFeedbackTimers.delete(id)
  operationFeedback.value = operationFeedback.value.filter((entry) => entry.id !== id)
}

function clearOperationFeedback() {
  for (const timer of operationFeedbackTimers.values()) window.clearTimeout(timer)
  operationFeedbackTimers.clear()
  operationFeedback.value = []
}

function retrySafeRefresh() {
  // A retry only reads authoritative state; it never repeats a queued command.
  loadQueue()
  if (playerAccount.value) refreshLoggedInPlayerQueue()
}

const totalRegistrationCount = computed(() => (
  machines.value.reduce((total, machine) => total + machine.registrationCount, 0)
))

const machineCountSummary = computed(() => (
  machines.value.length === 1
    ? '当前使用 1 台机台'
    : `${machines.value.length} 台机台的等待顺序彼此独立`
))

const venueHeading = computed(() => {
  const name = typeof venue.value?.name === 'string' ? venue.value.name.trim() : ''
  const code = typeof venue.value?.code === 'string' ? venue.value.code.trim() : ''
  if (!name && !code) return null
  return {
    name: name || '当前机厅',
    code: code || null
  }
})

const configuredMachineGroups = computed(() => machineGroups.value.map((group) => ({
  ...group,
  machines: machines.value.filter((machine) => machine.groupId === group.id)
})).filter((group) => group.machines.length > 0))

const activeMachineGroup = computed(() => (
  configuredMachineGroups.value.find((group) => group.id === activeMachineGroupId.value) ||
  configuredMachineGroups.value[0] || null
))

const visibleMachines = computed(() => activeMachineGroup.value?.machines ?? machines.value)

const clientVersionRows = computed(() => {
  const definitions = [
    { key: 'terminal', name: '现场终端' },
    { key: 'website', name: '网页端' },
    { key: 'bot', name: 'QQ Bot' }
  ]
  return definitions.map((definition) => {
    const source = clientVersions.value?.components?.[definition.key]
    const status = ['LATEST', 'UPDATE_AVAILABLE', 'AHEAD'].includes(source?.status)
      ? source.status
      : 'UNKNOWN'
    return {
      key: definition.key,
      name: typeof source?.name === 'string' && source.name.trim()
        ? source.name.trim()
        : definition.name,
      currentVersion: normalizeClientVersion(source?.current_version),
      latestVersion: normalizeClientVersion(source?.latest_version),
      updatedAt: source?.updated_at ?? null,
      status,
      statusLabel: {
        LATEST: '已是最新版本',
        UPDATE_AVAILABLE: '有新版本',
        AHEAD: '高于公开版本',
        UNKNOWN: '暂时无法确认'
      }[status]
    }
  })
})

const snapshotAgeMillis = computed(() => {
  const date = parseDate(capturedAt.value)
  return date ? Math.max(0, currentTime.value - date.getTime()) : null
})

const snapshotStale = computed(() => (
  hasSnapshot.value && (
    snapshotAgeMillis.value === null || snapshotAgeMillis.value > SNAPSHOT_STALE_AFTER
  )
))

const terminalOnline = computed(() => (
  hasSnapshot.value && !snapshotStale.value && terminal.value?.online !== false
))

const terminalStatusLabel = computed(() => {
  if (!hasSnapshot.value) return '尚未接入'
  if (snapshotStale.value) return '状态待更新'
  return terminal.value?.online === false ? '离线' : '在线'
})

const onlineRegistrationAvailable = computed(() => (
  hasSnapshot.value &&
  terminalOnline.value &&
  capabilities.value?.online_registration === true &&
  !businessHoursClosingGrace.value &&
  registrationOpen.value !== false &&
  machines.value.some((machine) => (
    machine.synced && machine.operational && machine.registrationCount < 20
  ))
))

const onlineRegistrationSummary = computed(() => {
  if (snapshotStale.value) return '队列数据已过期，暂不能线上加入排队'
  if (!hasSnapshot.value || !terminalOnline.value) return '现场终端离线，暂不能线上加入排队'
  if (businessHoursClosingGrace.value) return '闭店收尾期间不再接收新的排队登记'
  if (capabilities.value?.online_registration !== true) return '现场暂未开放线上登记'
  if (registrationOpen.value === false) {
    return outsideBusinessHours.value ? '当前不接收新的排队登记' : '当前采用现场自然排队'
  }
  if (!machines.value.some((machine) => (
    machine.synced && machine.operational && machine.registrationCount < 20
  ))) return '当前没有可以接收新登记的机台'
  return '使用现场玩家资料加入；创建登记后须在 30 分钟内到现场签到'
})

const onlineJoinMachineOptions = computed(() => {
  const remote = onlineJoinMachines.value
  if (remote.length) return remote
  return machines.value.map((machine) => ({
    id: machine.id,
    stableId: machine.stableId,
    groupId: machine.groupId,
    name: machine.name,
    configuration: machine.configuration,
    capacity: machine.capacity,
    operational: machine.operational,
    registrationCount: machine.registrationCount,
    // The public queue carries no wait of a new registration; the rows leave it out until the
    // profile has been looked up, which returns the machines with it.
    estimatedWaitMinutes: null,
    estimatedWaitKnown: false,
    available: !businessHoursClosingGrace.value && machine.synced &&
      machine.operational && machine.registrationCount < 20,
    unavailableReason: !machine.operational
      ? '机台已停止使用'
      : machine.registrationCount >= 20 ? '登记已满' : null
  }))
})

const selectedOnlineJoinMachine = computed(() => (
  onlineJoinMachineOptions.value.find((machine) => machine.id === onlineJoinMachineId.value) || null
))

const onlineJoinSinglePlayerMachine = computed(() => (
  selectedOnlineJoinMachine.value?.capacity === 1
))

const onlineJoinNeedsPreference = computed(() => (
  !onlineJoinSinglePlayerMachine.value &&
  onlineJoinProfile.value?.defaultPreference === 'ASK_EVERY_TIME'
))

// The machine that forms a pair with the chosen one, if the venue has set one up.
const onlineJoinPairedMachine = computed(() => {
  const pairedMachineId = selectedOnlineJoinMachine.value?.pairedMachineId
  if (!pairedMachineId) return null
  return onlineJoinMachineOptions.value.find((machine) => machine.id === pairedMachineId) || null
})

// Whether the system may arrange the registration being created between the chosen machine
// and the machine paired with it. Unticked unless the player ticks it; no profile stores it.
const onlineJoinPairedTick = computed({
  get() {
    const answer = onlineJoinPairedAnswer.value
    return Boolean(
      answer &&
      answer.machineId === onlineJoinMachineId.value &&
      answer.profileId === (onlineJoinProfile.value?.profileId ?? null) &&
      answer.queuesAtBoth
    )
  },
  set(queuesAtBoth) {
    if (!onlineJoinPairedMachine.value || onlineJoinLoading.value) return
    onlineJoinPairedAnswer.value = {
      machineId: onlineJoinMachineId.value,
      profileId: onlineJoinProfile.value?.profileId ?? null,
      queuesAtBoth: queuesAtBoth === true
    }
    // Another answer is another request for the server.
    onlineJoinCommandId.value = null
    onlineJoinError.value = ''
  }
})

// The tick box that lets others pass sits inside 单人游玩 of the preference step, where the
// terminal knows the choice.
const onlineJoinPassingAvailable = computed(() => (
  (onlineJoinNeedsPreference.value || onlineJoinSoloByDefault.value) &&
  terminalSupportsQueueOperation(capabilities.value, 'SET_ALLOWS_PASSING')
))

// A profile that always plays alone gets the same tick box, on its own, as on the terminal.
const onlineJoinSoloByDefault = computed(() => (
  !onlineJoinSinglePlayerMachine.value &&
  onlineJoinProfile.value?.defaultPreference === 'SOLO'
))

const onlineJoinPassing = computed({
  get() {
    const answer = onlineJoinPassingAnswer.value
    if (!onlineJoinPassingAvailable.value) return false
    if (!onlineJoinSoloByDefault.value && onlineJoinPreference.value !== 'SOLO') return false
    const answered = answer &&
      answer.machineId === onlineJoinMachineId.value &&
      answer.profileId === (onlineJoinProfile.value?.profileId ?? null)
    // Until the player answers here, the box shows the profile's last answer (maintainer,
    // 2026-11-26: "如果是玩家资料的话，这个勾选框记忆上次状态").
    return answered ? answer.allowsPassing === true : onlineJoinProfile.value?.lastAllowsPassing === true
  },
  set(allowsPassing) {
    if (!onlineJoinPassingAvailable.value || onlineJoinLoading.value) return
    onlineJoinPassingAnswer.value = {
      machineId: onlineJoinMachineId.value,
      profileId: onlineJoinProfile.value?.profileId ?? null,
      allowsPassing: allowsPassing === true
    }
    // Another answer is another request for the server.
    onlineJoinCommandId.value = null
    onlineJoinError.value = ''
  }
})

// The online join has no 将本次选择保存为默认游玩偏好 row: anyone may type a QQ number here, and
// a profile's default preference is not theirs to change. A signed-in player changes it under
// 资料设置 (maintainer, 2026-11-28: “干脆把这一行去掉都没事”). The phone page, used on site,
// keeps the row as the terminal does.

const onlineJoinCanSubmit = computed(() => (
  selectedOnlineJoinMachine.value?.available === true &&
  (!onlineJoinNeedsPreference.value || ['SOLO', 'OPEN_TO_JOIN'].includes(onlineJoinPreference.value))
))

const availability = computed(() => {
  if (!hasSnapshot.value) {
    return { label: loading.value ? '正在连接排队终端' : '现场终端暂未接入', tone: 'offline' }
  }
  if (snapshotStale.value) return { label: '队列数据已过期', tone: 'offline' }
  if (loadError.value) return { label: '连接暂时中断', tone: 'closed' }
  if (!terminalOnline.value) return { label: '现场终端离线', tone: 'offline' }
  if (businessHours.value.enabled && businessHours.value.outside) {
    return { label: '不在营业时间', tone: 'outside' }
  }
  if (registrationOpen.value === false) return { label: '当前采用现场自然排队', tone: 'closed' }
  return null
})

const outsideBusinessHours = computed(() => (
  businessHours.value.enabled && businessHours.value.outside
))

const businessHoursClosingGrace = computed(() => (
  terminalOnline.value &&
  businessHours.value.enabled &&
  businessHours.value.outside &&
  businessHours.value.closingGrace
))

const businessHoursClosingSoon = computed(() => (
  terminalOnline.value &&
  businessHours.value.enabled &&
  !businessHours.value.outside &&
  businessHours.value.closingSoon
))

const capturedTimeText = computed(() => {
  const date = parseDate(capturedAt.value)
  if (!date) return '--:--'
  if (snapshotStale.value) {
    return `更新于 ${date.toLocaleString('zh-CN', {
      month: 'numeric',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })}`
  }
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
})

const registrationLocations = computed(() => {
  const locations = []
  machines.value.forEach((machine) => {
    machine.playing.forEach((registration) => {
      locations.push({
        registration,
        machine,
        kind: 'PLAYING',
        label: `游玩位置 ${machine.id}`,
        estimate: 0,
        estimateUncertain: false,
        registrations: machine.playing
      })
    })
    machine.waitingPositions.forEach((position, index) => {
      position.registrations.forEach((registration) => {
        const estimate = registrationWaitEstimate(
          registration,
          position.estimatedWaitMinutes,
          position.estimatedWaitUncertain,
          position.estimatedWaitUpperMinutes
        )
        locations.push({
          registration,
          machine,
          kind: 'WAITING',
          label: `位置 ${machine.id}${index + 1}`,
          estimate: estimate.minutes,
          estimateUncertain: estimate.uncertain,
          estimateUpper: estimate.upper,
          registrations: position.registrations,
          commonPlayPreview: position.commonPlayPreview
        })
      })
    })
  })
  return locations
})

const markedSelfResolution = computed(() => {
  // Logged-in accounts are resolved only by server-issued registration IDs.
  // QQ/nickname matching remains available only for the legacy local marker.
  if (playerAccount.value) {
    const accountIds = new Set(accountRegistrationIds())
    const accountMatches = registrationLocations.value.filter((location) => (
      accountIds.has(location.registration.registrationId)
    ))
    return {
      location: accountMatches.length === 1 ? accountMatches[0] : null,
      ambiguous: accountMatches.length > 1 || accountRegistrationIds().length > 1
    }
  }
  const identity = markedSelf.value
  if (!identity) return { location: null, ambiguous: false }

  const exactLocation = registrationLocations.value.find((location) => (
    location.registration.registrationId === identity.registrationId
  ))
  if (exactLocation) return { location: exactLocation, ambiguous: false }

  const qqNumber = normalizeQqNumber(identity.qqNumber)
  if (qqNumber) {
    const qqMatches = registrationLocations.value.filter((location) => (
      location.registration.qqNumber === qqNumber
    ))
    if (qqMatches.length === 1) return { location: qqMatches[0], ambiguous: false }
    if (qqMatches.length > 1) return { location: null, ambiguous: true }
  }

  const nickname = normalizePlayerNickname(identity.displayId)
  if (!nickname) return { location: null, ambiguous: false }
  const nicknameMatches = registrationLocations.value.filter((location) => (
    normalizePlayerNickname(location.registration.displayId) === nickname
  ))
  if (nicknameMatches.length === 1) {
    return { location: nicknameMatches[0], ambiguous: false }
  }
  return { location: null, ambiguous: nicknameMatches.length > 1 }
})

const markedSelfLocation = computed(() => markedSelfResolution.value.location)
const markedSelfAmbiguous = computed(() => markedSelfResolution.value.ambiguous)
const activeSelfIdentity = computed(() => playerAccount.value ? accountSelfIdentity.value : markedSelf.value)
const accountSessionActive = computed(() => Boolean(playerAccount.value))

const markedSelfLastEvent = computed(() => {
  const registrationIds = knownSelfRegistrationIds()
  if (!registrationIds.length) return null
  return markedSelfLogs.value.find((event) => (
    event.registrationIds.some((registrationId) => registrationIds.includes(registrationId))
  )) || null
})

const markedSelfPartnerText = computed(() => {
  const location = markedSelfLocation.value
  if (!location) return null
  const partners = location.registrations.filter((registration) => (
    registration.registrationId !== location.registration.registrationId
  ))
  if (partners.length) {
    const action = location.kind === 'PLAYING' ? '正在与' : '将与'
    return `${action}${partners.map((partner) => `“${partner.displayId}”`).join('、')}共同游玩`
  }
  if (location.commonPlayPreview) {
    return `预计与“${location.commonPlayPreview.displayId}”共同游玩`
  }
  return '当前没有共同游玩的登记'
})

const onlineJoinPostApplyEvent = computed(() => {
  const registrationId = onlineJoinResultRegistrationId.value
  if (!registrationId) return null
  if (
    onlineJoinQueueId.value && queueId.value &&
    onlineJoinQueueId.value !== queueId.value
  ) {
    return {
      type: 'QUEUE_RESET',
      title: '现场已经开始新的队列',
      detail: '现场已经开始新的队列，这份线上登记不再有效。',
      registrationIds: [registrationId]
    }
  }
  if (registrationLocations.value.some(({ registration }) => (
    registration.registrationId === registrationId
  ))) return null
  const terminalExitTypes = new Set([
    'ONLINE_CHECK_IN_TIMED_OUT',
    'ONLINE_CHECK_IN_MISSED',
    'REGISTRATION_REMOVED',
    'REGISTRATION_CLOSED',
    'QUEUE_RESET'
  ])
  return [...markedSelfLogs.value, ...currentLogs.value]
    .filter((event, index, events) => (
      events.findIndex((candidate) => candidate.eventId === event.eventId) === index
    ))
    .sort((first, second) => (second.cursor || 0) - (first.cursor || 0))
    .find((event) => (
      terminalExitTypes.has(event.type) && event.registrationIds.includes(registrationId)
    )) || null
})

const filteredLogs = computed(() => {
  const machineFiltered = logFilter.value === 'ALL'
    ? currentLogs.value
    : logFilter.value === 'SYSTEM'
      ? currentLogs.value.filter((event) => !event.machineId)
      : currentLogs.value.filter((event) => {
          if (event.machineStableId) return event.machineStableId === logFilter.value
          const selectedMachine = machines.value.find(
            (machine) => machine.stableId === logFilter.value
          )
          return event.machineId === selectedMachine?.id
        })
  return logSourceFilter.value === 'ALL'
    ? machineFiltered
    : machineFiltered.filter((event) => event.operationSource === logSourceFilter.value)
})

const logMachineFilters = computed(() => [
  { value: 'ALL', label: '全部' },
  ...machines.value.map((machine) => ({
    value: machine.stableId,
    label: `机台 ${machine.id}`
  })),
  { value: 'SYSTEM', label: '机厅' }
])

const onlineJoinMachineGroups = computed(() => {
  const groups = onlineJoinGroups.value.length
    ? onlineJoinGroups.value
    : configuredMachineGroups.value.map(({ id, name }) => ({ id, name }))
  const fallbackGroup = groups[0] || { id: DEFAULT_MACHINE_GROUP_ID, name: '分组 1' }
  const validGroupIds = new Set(groups.map((group) => group.id))
  return groups.map((group) => ({
    ...group,
    machines: onlineJoinMachineOptions.value.filter((machine) => (
      (validGroupIds.has(machine.groupId) ? machine.groupId : fallbackGroup.id) === group.id
    )).map((machine) => ({ ...machine, row: onlineJoinMachineRow(machine) }))
  })).filter((group) => group.machines.length > 0)
})

// The number of waiting positions of each machine by machine id: the machine's own positions,
// without previews of the other machine of a pair, as the terminal counts them in its machine
// lists. The account dialog takes them from here.
const waitingPositionCounts = computed(() => Object.fromEntries(
  machines.value.filter((machine) => machine.synced).map((machine) => [
    machine.id,
    (machine.ownWaitingPositions || machine.waitingPositions).length
  ])
))

// One row of the machine list while joining, as the terminal's 选择机台: what waits at the
// machine and the wait of a new registration, or the reason and a grey badge for a machine that
// is stopped or full.
function onlineJoinMachineRow(machine) {
  if (snapshotStale.value) return { description: '队列数据已过期', badge: '', wait: '' }
  if (!terminalOnline.value) return { description: '现场终端离线', badge: '', wait: '' }
  if (!machine.operational) {
    const pageMachine = machines.value.find((candidate) => candidate.id === machine.id)
    return {
      description: `机台已停止使用：${stopReasonLabel(pageMachine?.stopReason, pageMachine?.stopReasonDetail)}。`,
      badge: '已停止使用',
      wait: ''
    }
  }
  if (machine.registrationCount >= 20) {
    return { description: '机台已有 20 份登记，暂不能创建新的登记。', badge: '登记已满', wait: '' }
  }
  if (!machine.available) {
    return { description: machine.unavailableReason || '当前不可加入', badge: '', wait: '' }
  }
  return {
    description: machineQueueSummary({
      registrationCount: machine.registrationCount,
      waitingPositionCount: waitingPositionCounts.value[machine.id],
      emptyText: '当前没有登记，可以直接开始。'
    }),
    badge: '',
    wait: machine.estimatedWaitKnown === false ? '' : newRegistrationWaitText(machine.estimatedWaitMinutes)
  }
}

// 新登记预计等待 of the chosen machine, as the terminal shows it while joining. With the tick box
// for the two machines on, the registration may play at the other machine first: that machine's
// wait is shown when it is the earlier one, as the earliest.
const onlineJoinWaitSummary = computed(() => {
  const machine = selectedOnlineJoinMachine.value
  if (!machine?.available) return null
  const pairedMachine = onlineJoinPairedTick.value ? onlineJoinPairedMachine.value : null
  return newRegistrationWaitTextWithPaired(
    machine.estimatedWaitMinutes,
    pairedMachine?.available ? pairedMachine.estimatedWaitMinutes : null
  )
})

// Under the title 确认加入排队, in the terminal's two sentences.
const onlineJoinConfirmSubtitle = computed(() => {
  const machineName = selectedOnlineJoinMachine.value?.name
  if (!machineName) return ''
  return onlineJoinSinglePlayerMachine.value
    ? `请确认玩家资料。完成后会以单人游玩加入 ${machineName}。`
    : `请确认玩家资料和本次游玩偏好。完成后会加入 ${machineName}。`
})

// Whether a new registration at [machine] is likely to be reached only after closing time, by
// the terminal's rule (estimatedWaitExtendsPastClosing): its wait, counted from now, ends later
// than the closing time of the business hours in force.
function onlineJoinLikelyAfterClosing(machine) {
  const closesAt = parseDate(businessHours.value.closesAt)
  return waitExtendsPastClosing(machine?.estimatedWaitMinutes, closesAt ? closesAt.getTime() : null)
}

function onlineJoinNeedsClosingWarning(machine) {
  return Boolean(machine) &&
    !onlineJoinClosingAcknowledgedMachineIds.value.includes(machine.id) &&
    onlineJoinLikelyAfterClosing(machine)
}

// The sentence of the warning, as the terminal's JoinClosingWarningDialog writes it for one
// machine. Other machines are offered only where a new registration is not late there as well.
const onlineJoinClosingWarningText = computed(() => {
  const machine = onlineJoinMachineOptions.value.find((candidate) => (
    candidate.id === onlineJoinClosingWarning.value?.machineId
  ))
  if (!machine) return ''
  const closesAt = parseDate(businessHours.value.closesAt)
  return closingWarningText({
    machineName: machine.name,
    minutes: machine.estimatedWaitMinutes,
    closingTime: closesAt ? pairedQueueClockTime(closesAt.getTime()) : null,
    alternativeMachineNames: onlineJoinMachineOptions.value
      .filter((candidate) => (
        candidate.available && candidate.id !== machine.id && !onlineJoinLikelyAfterClosing(candidate)
      ))
      .map((candidate) => candidate.name)
  })
})

function createEmptyMachine(definition) {
  const configuration = normalizeMachineConfiguration(null, definition)
  return {
    ...definition,
    stableId: definition.stableId || defaultMachineStableId(definition.id),
    groupId: definition.groupId || DEFAULT_MACHINE_GROUP_ID,
    remark: configuration.remark,
    configuration,
    capacity: configuration.capacity,
    synced: false,
    operational: true,
    stopReason: null,
    stopReasonDetail: null,
    playingStartedAt: null,
    playing: [],
    waitingPositions: [],
    registrationCount: 0,
    pairedMachineId: null,
    pairedWaitingSource: null
  }
}

function defaultMachineStableId(machineId) {
  const index = SUPPORTED_MACHINE_IDS.indexOf(machineId)
  return Math.max(1, index + 1).toString(16).padStart(32, '0')
}

function normalizeInternalId(value) {
  const id = String(value || '').trim().toLowerCase()
  return /^[0-9a-f]{32}$/.test(id) ? id : null
}

function parseDate(value) {
  if (value === null || value === undefined || value === '') return null
  const normalizedValue = typeof value === 'number' && value < 1_000_000_000_000
    ? value * 1000
    : value
  const date = new Date(normalizedValue)
  return Number.isFinite(date.getTime()) ? date : null
}

function toNonNegativeInteger(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, Math.trunc(number)) : null
}

function normalizeQqNumber(value) {
  const qqNumber = typeof value === 'string' ? value.trim() : ''
  return /^\d{5,12}$/.test(qqNumber) ? qqNumber : null
}

function normalizeRegistration(source, index) {
  if (typeof source === 'string' || typeof source === 'number') {
    return {
      displayId: String(source),
      registrationId: null,
      preference: 'SOLO',
      deferredOnce: false,
      skipsOneTurn: false,
      temporarilyAway: false,
      temporaryAwaySkippedTurns: 0,
      fixedPair: false,
      fixedPairId: null,
      noShowCount: 0,
      lastNoShowActionWasDefer: false,
      onlineRegistrationPendingCheckIn: false,
      allowsPassing: false,
      passingOver: [],
      registrationType: 'TEMPORARY',
      qqNumber: null
    }
  }

  const displayId = source?.display_id ?? source?.displayId
  return {
    displayId: String(displayId || `登记 ${index + 1}`),
    registrationId: source?.registration_id ?? source?.registrationId ?? null,
    preference: String(source?.preference || 'SOLO').toUpperCase(),
    deferredOnce: source?.deferred_once === true || source?.deferredOnce === true,
    // 将轮空一次; older terminals leave it out.
    skipsOneTurn: source?.skips_one_turn === true || source?.skipsOneTurn === true,
    temporarilyAway: source?.temporarily_away === true || source?.temporarilyAway === true,
    temporaryAwaySkippedTurns: toNonNegativeInteger(
      source?.temporary_away_skipped_turns ?? source?.temporaryAwaySkippedTurns
    ) ?? 0,
    fixedPair: source?.fixed_pair === true || source?.fixedPair === true,
    fixedPairId: source?.fixed_pair_id ?? source?.fixedPairId ?? null,
    noShowCount: toNonNegativeInteger(source?.no_show_count ?? source?.noShowCount) ?? 0,
    lastNoShowActionWasDefer: source?.last_no_show_action_was_defer === true ||
      source?.lastNoShowActionWasDefer === true,
    onlineRegistrationPendingCheckIn:
      source?.online_registration_pending_check_in === true ||
      source?.onlineRegistrationPendingCheckIn === true,
    // 线上登记，已签到; older terminals and servers leave it out.
    onlineRegistrationCheckedIn:
      source?.online_registration_checked_in === true ||
      source?.onlineRegistrationCheckedIn === true,
    alsoQueuesAtPairedMachine:
      source?.also_queues_at_paired_machine === true ||
      source?.alsoQueuesAtPairedMachine === true,
    skipsPairedMachineThisRound:
      source?.skips_paired_machine_this_round === true ||
      source?.skipsPairedMachineThisRound === true,
    // A preview at the other machine announces that this registration may be moved there,
    // and from when on; the time is absent while the terminal holds the count.
    pairedArrangementAnnounced:
      source?.paired_arrangement_announced === true ||
      source?.pairedArrangementAnnounced === true,
    pairedArrangementNotBefore:
      toNonNegativeInteger(
        source?.paired_arrangement_not_before ?? source?.pairedArrangementNotBefore
      ),
    registrationType: String(
      source?.registration_type || source?.registrationType || 'TEMPORARY'
    ).toUpperCase(),
    qqNumber: normalizeQqNumber(source?.qq_number ?? source?.qqNumber),
    createdAt: source?.created_at ?? source?.createdAt ?? null,
    onlineCheckInStartedAt:
      source?.online_check_in_started_at ?? source?.onlineCheckInStartedAt ?? null,
    lastPlayedAt: source?.last_played_at ?? source?.lastPlayedAt ?? null,
    // The earlier minutes for when the pending online registrations ahead do not check in.
    waitMinutesIfOnlineMissing: toNonNegativeInteger(
      source?.wait_minutes_if_online_missing ?? source?.waitMinutesIfOnlineMissing
    ),
    // This registration's own wait, sent only where it differs from its position's (review
    // 2026-11-27); see registrationWaitEstimate. Older terminals leave both out.
    ownEstimatedWaitMinutes: toNonNegativeInteger(
      source?.own_estimated_wait_minutes ?? source?.ownEstimatedWaitMinutes
    ),
    ownEstimatedWaitUncertain:
      (source?.own_estimated_wait_uncertain ?? source?.ownEstimatedWaitUncertain) === true,
    // Beside an uncertain own wait: the wait if the system arranges nobody between the two
    // machines (noArrangementWaitNote).
    ownEstimatedWaitUpperMinutes:
      (source?.own_estimated_wait_uncertain ?? source?.ownEstimatedWaitUncertain) === true
        ? toNonNegativeInteger(source?.own_estimated_wait_upper_minutes ?? source?.ownEstimatedWaitUpperMinutes)
        : null,
    // A registration that plays alone and lets others pass over it, and the registrations this
    // one passes over (2026-11-26). Older terminals leave both out.
    allowsPassing: source?.allows_passing === true || source?.allowsPassing === true,
    passingOver: normalizeRegistrationIds(source?.passing_over ?? source?.passingOver)
  }
}

function normalizeRegistrationIds(value) {
  return Array.isArray(value)
    ? value.filter((registrationId) => typeof registrationId === 'string' && registrationId)
    : []
}

function normalizePosition(source, index) {
  const registrationsSource = Array.isArray(source) ? source : source?.registrations
  const registrations = Array.isArray(registrationsSource)
    ? registrationsSource.map(normalizeRegistration)
    : []
  const fixedPair = source?.fixed_pair === true || source?.fixedPair === true ||
    (registrations.length === 2 && registrations.every((registration) => registration.fixedPair))
  const estimatedWaitMinutes = toNonNegativeInteger(
    source?.estimated_wait_minutes ?? source?.estimatedWaitMinutes
  )

  return {
    registrations: fixedPair
      ? registrations.map((registration) => ({ ...registration, fixedPair: true }))
      : registrations,
    fixedPair,
    estimatedWaitMinutes,
    estimatedWaitUncertain: normalizeEstimatedWaitUncertain(source, estimatedWaitMinutes),
    estimatedWaitUpperMinutes: normalizeEstimatedWaitUpperMinutes(source, estimatedWaitMinutes),
    capacity: Number(source?.configuration?.capacity ?? source?.capacity) === 1 ? 1 : 2,
    positionId: source?.position_id ?? source?.positionId ?? null,
    commonPlayPreview: normalizeCommonPlayPreview(
      source?.common_play_preview ?? source?.commonPlayPreview
    ),
    index
  }
}

// The terminal marks an estimate as possibly inaccurate when the system may move registrations
// between the two machines of a pair; the minutes are then the earliest time. Older terminals
// leave the flag out, and a position without an estimate is never marked.
function normalizeEstimatedWaitUncertain(source, estimatedWaitMinutes) {
  return estimatedWaitMinutes !== null &&
    (source?.estimated_wait_uncertain ?? source?.estimatedWaitUncertain) === true
}

// Beside an uncertain wait only: the wait if the system arranges nobody between the two machines,
// shown on its own line (noArrangementWaitNote). Older terminals leave it out.
function normalizeEstimatedWaitUpperMinutes(source, estimatedWaitMinutes) {
  if (!normalizeEstimatedWaitUncertain(source, estimatedWaitMinutes)) return null
  return toNonNegativeInteger(source?.estimated_wait_upper_minutes ?? source?.estimatedWaitUpperMinutes)
}

function normalizeCommonPlayPreview(source) {
  if (!source || typeof source !== 'object') return null
  const displayId = String(source.display_id ?? source.displayId ?? '').trim()
  if (!displayId) return null
  return {
    registrationId: source.registration_id ?? source.registrationId ?? null,
    displayId
  }
}

function normalizeMachine(definition, source) {
  if (!source || typeof source !== 'object') return createEmptyMachine(definition)
  const configuration = normalizeMachineConfiguration(source, definition)
  const playingSource = Array.isArray(source.playing)
    ? source.playing
    : source.playing?.registrations
  const playing = Array.isArray(playingSource) ? playingSource.map(normalizeRegistration) : []
  const waitingSource = source.waiting_positions ?? source.waitingPositions
  const waitingPositions = Array.isArray(waitingSource)
    ? waitingSource.map(normalizePosition)
    : []

  return {
    ...definition,
    name: formatMiddleDots(String(source.name || definition.name)),
    stableId: definition.stableId,
    groupId: definition.groupId,
    remark: configuration.remark,
    configuration,
    capacity: configuration.capacity,
    synced: true,
    operational: source.operational !== false,
    stopReason: source.stop_reason ?? source.stopReason ?? null,
    stopReasonDetail: source.stop_reason_detail ?? source.stopReasonDetail ?? null,
    playingStartedAt: source.playing_started_at ?? source.playingStartedAt ?? null,
    playing,
    waitingPositions,
    registrationCount: playing.length + waitingPositions.reduce(
      (total, position) => total + position.registrations.length,
      0
    ),
    pairedMachineId: normalizePairedMachineId(
      source.paired_machine_id ?? source.pairedMachineId
    ),
    pairedWaitingSource: source.paired_waiting_positions ?? source.pairedWaitingPositions ?? null
  }
}

function normalizePairedMachineId(value) {
  const machineId = typeof value === 'string' ? value.trim().toUpperCase() : ''
  return SUPPORTED_MACHINE_IDS.includes(machineId) ? machineId : null
}

// A registration that queues at two machines stays listed under the machine it is registered
// at. When the terminal expects the other machine to reach it first, that machine publishes its
// waiting order again in `paired_waiting_positions`, referring to registrations by id. Those of
// the other machine become grey entries (`pairedPreviews`) beside the machine's own ones.
function applyPairedWaitingPositions(normalizedMachines) {
  const machinesById = new Map(normalizedMachines.map((machine) => [machine.id, machine]))
  const waitingRegistrationsById = (machine) => {
    const registrationsById = new Map()
    machine.waitingPositions.forEach((position) => {
      position.registrations.forEach((registration) => {
        if (registration.registrationId) {
          registrationsById.set(registration.registrationId, registration)
        }
      })
    })
    return registrationsById
  }

  return normalizedMachines.map((machine) => {
    const partner = machinesById.get(machine.pairedMachineId)
    const source = machine.pairedWaitingSource
    if (!partner || !Array.isArray(source) || !source.length) return machine

    const ownById = waitingRegistrationsById(machine)
    const partnerById = waitingRegistrationsById(partner)
    const positions = []
    let ownCount = 0
    let previewCount = 0
    for (const [index, positionSource] of source.entries()) {
      const entries = Array.isArray(positionSource?.registrations)
        ? positionSource.registrations
        : []
      const registrations = []
      const pairedPreviews = []
      for (const entry of entries) {
        const registrationId = entry?.registration_id ?? entry?.registrationId
        const isPreview = entry?.paired_place_preview === true ||
          entry?.pairedPlacePreview === true
        const registration = (isPreview ? partnerById : ownById).get(registrationId)
        // Data that does not add up is not shown half-way: the machine keeps its own order.
        if (!registration) return machine
        // A registration that gave up its place for this round holds none at this machine.
        if (isPreview && registration.skipsPairedMachineThisRound) return machine
        if (isPreview) {
          pairedPreviews.push({ ...registration, homeMachineId: partner.id })
        } else {
          registrations.push(registration)
        }
      }
      if (!registrations.length && !pairedPreviews.length) return machine
      ownCount += registrations.length
      previewCount += pairedPreviews.length
      const estimatedWaitMinutes = toNonNegativeInteger(
        positionSource?.estimated_wait_minutes ?? positionSource?.estimatedWaitMinutes
      )
      positions.push({
        registrations,
        pairedPreviews,
        fixedPair: registrations.length === 2 &&
          registrations.every((registration) => registration.fixedPair),
        estimatedWaitMinutes,
        estimatedWaitUncertain: normalizeEstimatedWaitUncertain(
          positionSource,
          estimatedWaitMinutes
        ),
        estimatedWaitUpperMinutes: normalizeEstimatedWaitUpperMinutes(
          positionSource,
          estimatedWaitMinutes
        ),
        capacity: machine.capacity,
        positionId: positionSource?.position_id ?? positionSource?.positionId ?? null,
        commonPlayPreview: normalizeCommonPlayPreview(
          positionSource?.common_play_preview ?? positionSource?.commonPlayPreview
        ),
        index
      })
    }
    if (ownCount !== ownById.size || !previewCount) return machine
    // The machine's own grouping is kept: what passes over what is told from it (soloPassCopy.js).
    return { ...machine, waitingPositions: positions, ownWaitingPositions: machine.waitingPositions }
  })
}

function machineSource(sources, definition) {
  if (Array.isArray(sources)) {
    return sources.find((source) => (
      String(source?.id ?? source?.machine_id).toUpperCase() === definition.id
    ))
  }
  return sources?.[definition.id] ?? sources?.[definition.id.toLowerCase()]
}

function machineDefinitionsFromSources(sources) {
  const entries = Array.isArray(sources)
    ? sources.map((source) => [source?.id ?? source?.machine_id, source])
    : Object.entries(sources)
  const normalizedEntries = entries.map(([key, source]) => ({
    id: String(source?.id ?? source?.machine_id ?? key ?? '').trim().toUpperCase(),
    source
  }))
  const configuredIds = SUPPORTED_MACHINE_IDS.slice(0, normalizedEntries.length)
  const sourceIds = normalizedEntries.map(({ id }) => id)
  if (
    !normalizedEntries.length ||
    normalizedEntries.length > SUPPORTED_MACHINE_IDS.length ||
    new Set(sourceIds).size !== sourceIds.length ||
    configuredIds.some((id) => !sourceIds.includes(id))
  ) {
    throw new Error('Invalid machine configuration')
  }
  return configuredIds.map((id) => ({
    definition: defaultMachineDefinitions.find((definition) => definition.id === id) || {
      id,
      name: `机台 ${id}`
    },
    source: normalizedEntries.find((entry) => entry.id === id).source
  }))
}

function normalizeMachineLayout(data, definitionsWithSources) {
  const rawGroups = data?.machine_groups ?? data?.machineGroups
  const groups = []
  const seenGroupIds = new Set()
  if (Array.isArray(rawGroups)) {
    rawGroups.forEach((source, index) => {
      const id = normalizeInternalId(source?.id)
      const name = String(source?.name || '').trim()
      if (id && name && !seenGroupIds.has(id)) {
        groups.push({ id, name: name.slice(0, 12), index })
        seenGroupIds.add(id)
      }
    })
  }
  if (!groups.length) groups.push({ id: DEFAULT_MACHINE_GROUP_ID, name: '分组 1', index: 0 })

  const requestedDefaultId = normalizeInternalId(
    data?.default_machine_group_id ?? data?.defaultMachineGroupId
  )
  const fallbackGroupId = groups.some((group) => group.id === requestedDefaultId)
    ? requestedDefaultId
    : groups[0].id
  const usedStableIds = new Set()
  const definitions = definitionsWithSources.map(({ definition, source }, index) => {
    const sourceStableId = normalizeInternalId(source?.stable_id ?? source?.stableId)
    let stableId = sourceStableId && !usedStableIds.has(sourceStableId)
      ? sourceStableId
      : defaultMachineStableId(definition.id)
    if (usedStableIds.has(stableId)) stableId = `${index + 1}`.padStart(32, 'f').slice(-32)
    usedStableIds.add(stableId)
    const sourceGroupId = normalizeInternalId(source?.group_id ?? source?.groupId)
    const groupId = groups.some((group) => group.id === sourceGroupId)
      ? sourceGroupId
      : fallbackGroupId
    return { ...definition, stableId, groupId }
  })

  const usedGroupIds = new Set(definitions.map(({ groupId }) => groupId))
  const configuredGroups = groups
    .filter((group) => usedGroupIds.has(group.id))
    .map(({ id, name }) => ({ id, name }))
  const normalizedDefaultId = configuredGroups.some((group) => group.id === requestedDefaultId)
    ? requestedDefaultId
    : configuredGroups[0]?.id || DEFAULT_MACHINE_GROUP_ID
  return { definitions, groups: configuredGroups, defaultGroupId: normalizedDefaultId }
}

function normalizeBusinessHours(source) {
  if (!source || typeof source !== 'object') {
    return {
      enabled: false,
      outside: false,
      closingSoon: false,
      closingGrace: false,
      closesAt: null,
      registrationClosesAt: null
    }
  }
  const enabled = source.enabled === true
  return {
    enabled,
    outside: enabled && (source.outside === true || source.outsideBusinessHours === true),
    closingSoon: enabled && (source.closing_soon === true || source.closingSoon === true),
    closingGrace: enabled && (source.closing_grace === true || source.closingGrace === true),
    closesAt: source.closes_at ?? source.closesAt ?? null,
    registrationClosesAt: source.registration_closes_at ?? source.registrationClosesAt ?? null
  }
}

function applyServerData(data) {
  const sources = data?.machines
  if (!sources || typeof sources !== 'object') throw new Error('Invalid queue snapshot')

  const previousQueueId = queueId.value
  const nextQueueId = data?.queue_id ?? data?.queueId ?? null
  const nextMachineConfigurationRevision = Math.max(
    1,
    toNonNegativeInteger(
      data?.machine_configuration_revision ?? data?.machineConfigurationRevision
    ) || 1
  )
  if (previousQueueId && nextQueueId && previousQueueId !== nextQueueId) {
    selectedDetail.value = null
    currentLogs.value = []
    currentLogsQueueId.value = null
    currentLogsNextCursor.value = null
  }
  const definitionsWithSources = machineDefinitionsFromSources(sources)
  const layout = normalizeMachineLayout(data, definitionsWithSources)
  const normalizedMachines = applyPairedWaitingPositions(
    layout.definitions.map((definition, index) => (
      normalizeMachine(definition, definitionsWithSources[index].source)
    ))
  )
  if (
    ['CONFIRM', 'EXISTING'].includes(onlineJoinStep.value) &&
    (
      (onlineJoinQueueId.value && nextQueueId !== onlineJoinQueueId.value) ||
      (
        onlineJoinMachineConfigurationRevision.value &&
        nextMachineConfigurationRevision !== onlineJoinMachineConfigurationRevision.value
      )
    )
  ) {
    invalidateOnlineJoinConfirmation('现场队列或机台配置已更新，请重新查询玩家资料后再提交。')
  }
  machines.value = normalizedMachines
  machineConfigurationRevision.value = nextMachineConfigurationRevision
  machineGroups.value = layout.groups
  defaultMachineGroupId.value = layout.defaultGroupId
  const availableGroupIds = new Set(layout.groups.map((group) => group.id))
  if (!hasSnapshot.value || !availableGroupIds.has(activeMachineGroupId.value)) {
    activeMachineGroupId.value = layout.defaultGroupId
  }
  const machineIds = new Set(normalizedMachines.map((machine) => machine.id))
  const machineStableIds = new Set(normalizedMachines.map((machine) => machine.stableId))
  if (!['ALL', 'SYSTEM'].includes(logFilter.value) && !machineStableIds.has(logFilter.value)) {
    logFilter.value = 'ALL'
  }
  if (!machineIds.has(onlineJoinMachineId.value)) {
    onlineJoinMachineId.value = normalizedMachines.find((machine) => (
      machine.synced && machine.operational && machine.registrationCount < 20
    ))?.id || normalizedMachines[0].id
  }
  registrationOpen.value = data?.registration_open ?? data?.registrationOpen ?? true
  businessHours.value = normalizeBusinessHours(data?.business_hours ?? data?.businessHours)
  terminal.value = data?.terminal ?? { online: true }
  venue.value = data?.venue && typeof data.venue === 'object' ? {
    name: String(data.venue.name || '').trim(),
    code: String(data.venue.code || '').trim()
  } : null
  capabilities.value = data?.capabilities || {}
  testData.value = data?.test_data === true || data?.testData === true
  queueId.value = nextQueueId
  capturedAt.value = data?.received_at ?? data?.receivedAt ??
    data?.captured_at ?? data?.capturedAt ?? new Date().toISOString()
  hasSnapshot.value = true
  loadError.value = false
  reconcileSelectedDetail()
  reconcileMarkedSelfIdentity()
}

async function loadQueue(silent = false) {
  if (refreshing.value) return
  refreshing.value = true
  if (!silent && !hasSnapshot.value) loading.value = true
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)

  try {
    const response = await fetch(QUEUE_API_URL, {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal: controller.signal
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    applyServerData(await response.json())
    if (playerAccount.value) await refreshLoggedInPlayerQueue()
    await refreshMarkedSelfLogs()
    if (activeView.value === 'logs') await loadCurrentLogs(true)
  } catch {
    loadError.value = true
  } finally {
    window.clearTimeout(timeout)
    loading.value = false
    refreshing.value = false
  }
}

function normalizeClientVersion(value) {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (
    trimmed.length > 32 ||
    !/^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(trimmed)
  ) return null
  return trimmed.startsWith('v') ? trimmed.slice(1) : trimmed
}

async function loadClientVersions() {
  if (clientVersionsLoading.value) return
  clientVersionsLoading.value = true
  clientVersionsError.value = false
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(QUEUE_VERSIONS_API_URL, {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal: controller.signal
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const data = await response.json()
    if (!data?.components || typeof data.components !== 'object') {
      throw new Error('Invalid version response')
    }
    clientVersions.value = data
  } catch {
    clientVersionsError.value = true
  } finally {
    window.clearTimeout(timeout)
    clientVersionsLoading.value = false
  }
}

function openVersionDialog() {
  versionDialogVisible.value = true
  loadClientVersions()
}

function closeVersionDialog() {
  versionDialogVisible.value = false
}

function syncAccountSelfIdentity() {
  const account = playerAccount.value
  const state = playerAccountQueueState.value
  if (!account || !state) {
    accountSelfIdentity.value = null
    return
  }
  const registrations = Array.isArray(state.registrations) ? state.registrations : []
  const profile = account.profile || {}
  const registrationIds = registrations
    .map((registration) => registration?.registration_id)
    .filter((registrationId) => typeof registrationId === 'string' && registrationId)
  const onlyRegistration = registrations.length === 1 ? registrations[0] : null
  accountSelfIdentity.value = {
    isAccount: true,
    queueId: state.queue?.queue_id || queueId.value,
    registrationId: onlyRegistration?.registration_id || null,
    registrationIds,
    displayId: onlyRegistration?.display_id || profile.nickname || '已登录玩家',
    qqNumber: normalizeQqNumber(profile.qq_number),
    machineId: onlyRegistration?.machine_id || null
  }
}

function handlePlayerAccountSession(account) {
  const currentProfile = playerAccount.value?.profile || null
  const nextProfile = account?.profile || null
  const profileKey = (profile) => profile?.public_player_id || profile?.qq_number || ''
  const accountChanged = profileKey(currentProfile) !== profileKey(nextProfile)
  playerAccount.value = account || null
  if (!account) {
    playerAccountQueueState.value = null
    accountSelfIdentity.value = null
    markedSelfLogs.value = []
    return
  }
  if (accountChanged) {
    playerAccountQueueState.value = null
    accountSelfIdentity.value = null
  }
  syncAccountSelfIdentity()
}

function handlePlayerAccountQueueState(state) {
  playerAccountQueueState.value = state || null
  syncAccountSelfIdentity()
  // The dialog reads its queue again after every command; the public queue follows, so that
  // what the dialog learns from it (whether its registration is being passed over) is as new.
  if (state && playerAccountDialogVisible.value) loadQueue(true)
}

async function refreshLoggedInPlayerQueue() {
  if (!playerAccount.value) return
  try {
    const response = await fetch(`${PLAYER_ACCOUNT_API_URL}/queue`, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    })
    if (response.status === 401) {
      handlePlayerAccountSession(null)
      return
    }
    if (!response.ok) return
    handlePlayerAccountQueueState(await response.json())
  } catch {
    // The public queue remains usable if the optional account refresh fails.
  }
}

async function refreshLoggedInPlayerSession() {
  if (playerAccountBindingToken.value) return
  try {
    const response = await fetch(PLAYER_ACCOUNT_API_URL, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    })
    if (response.status === 401) return
    if (!response.ok) return
    const payload = await response.json()
    if (!payload?.account) return
    handlePlayerAccountSession(payload.account)
    await refreshLoggedInPlayerQueue()
  } catch {
    // Account detection is best effort and must never block queue status.
  } finally {
    playerAccountSessionReady.value = true
  }
}

function openPlayerAccount(focusRegistrationId = '') {
  playerAccountFocusRegistrationId.value = focusRegistrationId || ''
  playerAccountDialogVisible.value = true
}

function closePlayerAccount() {
  playerAccountDialogVisible.value = false
  playerAccountFocusRegistrationId.value = ''
  if (playerAccountBindingToken.value) {
    playerAccountBindingToken.value = ''
    const url = new URL(window.location.href)
    url.searchParams.delete('account_binding')
    window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`)
  }
}

function handlePlayerAccountBound() {
  playerAccountBindingToken.value = ''
  const url = new URL(window.location.href)
  url.searchParams.delete('account_binding')
  window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`)
}

function normalizeLogEvent(source) {
  return {
    cursor: toNonNegativeInteger(source?.cursor),
    eventId: source?.event_id ?? source?.eventId ?? null,
    occurredAt: source?.occurred_at ?? source?.occurredAt ?? null,
    machineId: source?.machine_id ?? source?.machineId ?? null,
    machineStableId: normalizeInternalId(
      source?.machine_stable_id ?? source?.machineStableId
    ),
    machineName: formatMiddleDots(
      String(source?.machine_name ?? source?.machineName ?? '').trim()
    ) || null,
    type: String(source?.type || 'OTHER').toUpperCase(),
    title: formatMiddleDots(String(source?.title || '队列已更新')),
    detail: formatMiddleDots(String(source?.detail || '')),
    operationSource: String(
      source?.operation_source ?? source?.operationSource ?? 'ON_SITE_TERMINAL'
    ).toUpperCase(),
    registrationIds: Array.isArray(source?.registration_ids)
      ? source.registration_ids.map(String)
      : []
  }
}

async function fetchLogs(targetQueueId, before = null, limit = 50) {
  const url = new URL(QUEUE_LOG_API_URL, window.location.href)
  if (targetQueueId) url.searchParams.set('queue_id', targetQueueId)
  url.searchParams.set('limit', String(limit))
  if (before) url.searchParams.set('before', String(before))
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(url, {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal: controller.signal
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const data = await response.json()
    return {
      logs: Array.isArray(data?.logs) ? data.logs.map(normalizeLogEvent) : [],
      nextCursor: toNonNegativeInteger(data?.next_cursor),
      queueId: data?.queue_id ?? targetQueueId
    }
  } finally {
    window.clearTimeout(timeout)
  }
}

async function loadCurrentLogs(reset = true) {
  if (!queueId.value || logsLoading.value || logsLoadingMore.value) return
  const requestedQueueId = queueId.value
  if (reset) logsLoading.value = true
  else logsLoadingMore.value = true
  logsError.value = false
  try {
    const result = await fetchLogs(
      requestedQueueId,
      reset ? null : currentLogsNextCursor.value
    )
    if (requestedQueueId !== queueId.value) return
    currentLogs.value = reset
      ? result.logs
      : [...currentLogs.value, ...result.logs.filter((event) => (
        !currentLogs.value.some((existing) => existing.eventId === event.eventId)
      ))]
    currentLogsQueueId.value = requestedQueueId
    currentLogsNextCursor.value = result.nextCursor
  } catch {
    logsError.value = true
  } finally {
    logsLoading.value = false
    logsLoadingMore.value = false
  }
}

async function refreshMarkedSelfLogs() {
  if (playerAccount.value && !accountSelfIdentity.value) {
    markedSelfLogs.value = []
    return
  }
  const identity = accountSelfIdentity.value || markedSelf.value
  if (!identity?.queueId) {
    markedSelfLogs.value = []
    return
  }
  try {
    const result = await fetchLogs(identity.queueId, null, 100)
    const trackedQueueId = accountSelfIdentity.value?.queueId || markedSelf.value?.queueId
    if (trackedQueueId === identity.queueId) {
      markedSelfLogs.value = result.logs
      if (identity.queueId === currentLogsQueueId.value) {
        currentLogs.value = result.logs
        currentLogsNextCursor.value = result.nextCursor
      }
    }
  } catch {
    markedSelfLogs.value = []
  }
}

function switchView(view) {
  activeView.value = view
  if (view === 'logs') loadCurrentLogs(true)
}

function selectMachineGroup(groupId) {
  if (configuredMachineGroups.value.some((group) => group.id === groupId)) {
    activeMachineGroupId.value = groupId
  }
}

function showMachineGroup(machine) {
  if (machine?.groupId) selectMachineGroup(machine.groupId)
}

function restoreMarkedSelf() {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(SELF_STORAGE_KEY) || 'null')
    if (
      parsed && typeof parsed.queueId === 'string' &&
      typeof parsed.registrationId === 'string' && typeof parsed.displayId === 'string'
    ) {
      persistMarkedSelf(parsed)
    }
  } catch {
    selfStorageAvailable.value = false
    try {
      window.localStorage.removeItem(SELF_STORAGE_KEY)
    } catch {
      // Some browsers disable local storage entirely. The mark can still work for this page view.
    }
  }
}

function requestMarkAsSelf(registration) {
  if (accountSessionActive.value) return
  if (!registration.registrationId || !queueId.value) return
  const nextIdentity = {
    queueId: queueId.value,
    registrationId: registration.registrationId,
    registrationIds: [
      registration.registrationId,
      ...knownSelfRegistrationIds()
    ],
    displayId: registration.displayId,
    qqNumber: normalizeQqNumber(registration.qqNumber),
    markedAt: Date.now(),
    machineId: selectedDetail.value?.machine?.id || null
  }
  if (
    markedSelf.value &&
    !isSameMarkedPlayer(markedSelf.value, nextIdentity)
  ) {
    pendingSelfRegistration.value = nextIdentity
    selectedDetail.value = null
    return
  }
  saveMarkedSelf(nextIdentity)
  showMachineGroup(selectedDetail.value?.machine)
  selectedDetail.value = null
}

function confirmReplaceMarkedSelf() {
  if (pendingSelfRegistration.value) {
    saveMarkedSelf(pendingSelfRegistration.value)
    showMachineGroup(machines.value.find((machine) => (
      machine.id === pendingSelfRegistration.value.machineId
    )))
  }
  pendingSelfRegistration.value = null
}

function saveMarkedSelf(identity) {
  persistMarkedSelf(identity)
  refreshMarkedSelfLogs()
}

function persistMarkedSelf(identity) {
  const normalizedIdentity = normalizeMarkedSelfIdentity(identity)
  markedSelf.value = normalizedIdentity
  try {
    window.localStorage.setItem(SELF_STORAGE_KEY, JSON.stringify(normalizedIdentity))
    selfStorageAvailable.value = true
  } catch {
    selfStorageAvailable.value = false
  }
}

function normalizeMarkedSelfIdentity(identity) {
  const registrationIds = [...new Set([
    identity?.registrationId,
    ...(Array.isArray(identity?.registrationIds) ? identity.registrationIds : [])
  ].filter((registrationId) => typeof registrationId === 'string' && registrationId))]
    .slice(0, MAX_SELF_REGISTRATION_HISTORY)
  return {
    ...identity,
    qqNumber: normalizeQqNumber(identity?.qqNumber),
    registrationIds
  }
}

function isSameMarkedPlayer(first, second) {
  const firstQqNumber = normalizeQqNumber(first?.qqNumber)
  const secondQqNumber = normalizeQqNumber(second?.qqNumber)
  if (firstQqNumber && secondQqNumber) return firstQqNumber === secondQqNumber
  return normalizePlayerNickname(first?.displayId) === normalizePlayerNickname(second?.displayId)
}

function accountRegistrationIds() {
  return Array.isArray(playerAccountQueueState.value?.registrations)
    ? playerAccountQueueState.value.registrations
      .map((registration) => registration.registration_id)
      .filter((registrationId) => typeof registrationId === 'string' && registrationId)
    : []
}

function knownSelfRegistrationIds(identity = accountSelfIdentity.value || markedSelf.value) {
  const accountIds = accountRegistrationIds()
  if (playerAccount.value) return [...new Set(accountIds)]
  if (!identity) return [...new Set(accountIds)]
  return [...new Set([
    ...accountIds,
    ...normalizeMarkedSelfIdentity(identity).registrationIds
  ])]
}

function normalizePlayerNickname(value) {
  return String(value || '').trim().normalize('NFC').toLocaleLowerCase('zh-CN')
}

function reconcileSelectedDetail() {
  const detail = selectedDetail.value
  if (!detail) return

  if (detail.kind === 'pairedPreview') {
    // The explanation stays open only while the entry it explains is still shown.
    const machine = machines.value.find(({ id }) => id === detail.machine.id)
    const preview = machine?.waitingPositions
      .flatMap((position) => position.pairedPreviews || [])
      .find(({ registrationId }) => registrationId === detail.registration.registrationId)
    if (!machine || !preview) {
      selectedDetail.value = null
      return
    }
    openPairedPreview(machine, preview)
    return
  }

  if (detail.kind === 'machine') {
    const machine = machines.value.find(({ stableId }) => stableId === detail.machine.stableId)
    if (!machine) {
      selectedDetail.value = null
      return
    }
    openMachineDetails(machine)
    return
  }

  if (detail.kind === 'registration') {
    const location = registrationLocations.value.find(({ registration }) => (
      registration.registrationId && registration.registrationId === detail.registration.registrationId
    ))
    if (!location) {
      selectedDetail.value = null
      return
    }
    openRegistration(
      location.machine,
      location.registration,
      location.label,
      location.kind === 'PLAYING' ? null : location.estimate,
      location.kind !== 'PLAYING' && location.estimateUncertain,
      location.registrations,
      location.commonPlayPreview,
      location.kind === 'PLAYING',
      true
    )
    return
  }

  const machine = machines.value.find(({ id }) => id === detail.machine.id)
  const position = detail.isPlaying
    ? null
    : machine?.waitingPositions.find(({ positionId }) => positionId === detail.positionId)
  if (!machine || (detail.isPlaying ? machine.playing.length === 0 : !position)) {
    selectedDetail.value = null
    return
  }
  openPosition(machine, position)
}

function reconcileMarkedSelfIdentity() {
  if (playerAccount.value) return
  const identity = markedSelf.value
  const location = markedSelfResolution.value.location
  if (!identity || !location?.registration.registrationId || !queueId.value) return

  const registration = location.registration
  if (
    identity.queueId === queueId.value &&
    identity.registrationId === registration.registrationId &&
    identity.displayId === registration.displayId &&
    normalizeQqNumber(identity.qqNumber) === registration.qqNumber
  ) return

  persistMarkedSelf({
    ...identity,
    queueId: queueId.value,
    registrationId: registration.registrationId,
    registrationIds: [registration.registrationId, ...knownSelfRegistrationIds(identity)],
    displayId: registration.displayId,
    qqNumber: registration.qqNumber,
    lastMatchedAt: Date.now()
  })
}

function clearMarkedSelf() {
  markedSelf.value = null
  markedSelfLogs.value = []
  pendingSelfRegistration.value = null
  try {
    window.localStorage.removeItem(SELF_STORAGE_KEY)
  } catch {
    selfStorageAvailable.value = false
  }
}

function isMarkedRegistration(registration) {
  if (accountRegistrationIds().includes(registration.registrationId)) return true
  return Boolean(markedSelfLocation.value?.registration.registrationId &&
    markedSelfLocation.value.registration.registrationId === registration.registrationId)
}

function isAccountRegistration(registration) {
  return accountRegistrationIds().includes(registration.registrationId)
}

function stopReasonLabel(reason, detail = null) {
  const normalizedReason = String(reason || '').toUpperCase()
  const labels = {
    NOT_POWERED_ON: '机台未开机',
    NETWORK_DISCONNECTED: '机台断网',
    MAINTENANCE: '机台维护',
    OTHER: '其他原因'
  }
  const label = labels[normalizedReason] || '原因未记录'
  const normalizedDetail = String(detail || '').trim().slice(0, 40)
  // The reason written in, when there is one, rather than 其他原因 (terminal, 2026-11-28).
  return normalizedReason === 'OTHER' && normalizedDetail ? normalizedDetail : label
}

function machineSummary(machine) {
  if (!machine.synced) return '尚未同步现场状态'
  // A position that holds only registrations of the other machine is a forecast, not a place
  // taken on this machine.
  const waitingPositionCount = machine.waitingPositions
    .filter((position) => position.registrations.length).length
  const queueSummary = `${waitingPositionCount} 个等待位置·${machine.registrationCount} 份登记`
  if (!machine.operational) return formatMiddleDots(`${queueSummary}·已停止使用`)
  return machine.registrationCount > 0 ? queueSummary : '当前空闲'
}

function machineGameTypeLabel(configuration) {
  if (configuration.gameType === 'OTHER') return configuration.customGameType || '其他'
  return {
    MAIMAI_DX: '舞萌 DX',
    CHUNITHM: '中二节奏',
    ONGEKI: 'Ongeki',
    DANCE_CUBE: '舞立方',
    TAIKO_NO_TATSUJIN: '太鼓达人'
  }[configuration.gameType] || '舞萌 DX'
}

function machineServerLabel(configuration) {
  if (configuration.server === 'HIDDEN') return null
  if (configuration.server === 'OTHER') return configuration.customServer || '其他'
  return {
    CHINA: '中国',
    INTERNATIONAL: '国际',
    JAPAN: '日本',
    DABING: '大饼',
    RINNET: 'RinNET'
  }[configuration.server] || null
}

function machineRoundTimeLabel(configuration) {
  if (configuration.capacity === 1) {
    return `单人游玩 ${configuration.soloRoundMinutes} 分钟`
  }
  return `单人游玩 ${configuration.soloRoundMinutes} 分钟；共同游玩 ${configuration.sharedRoundMinutes} 分钟`
}

function machineGroupName(machine) {
  return machineGroups.value.find((group) => group.id === machine.groupId)?.name || '分组 1'
}

function playingLabel(machine) {
  const base = `游玩位置 ${machine.id}`
  if (!machine.playing.length) return base
  if (!terminalOnline.value) return `${base}·状态待更新`
  if (!machine.operational) return `${base}·已暂停`
  const startedAt = parseDate(machine.playingStartedAt)
  if (!startedAt) return base
  const minutes = Math.max(0, Math.floor((currentTime.value - startedAt.getTime()) / 60000))
  return minutes === 0 ? `${base}·刚刚` : `${base}·已游玩 ${minutes} 分钟`
}

function playingOvertime(machine) {
  if (!terminalOnline.value) return false
  if (!machine.operational) return false
  const startedAt = parseDate(machine.playingStartedAt)
  return Boolean(startedAt && currentTime.value - startedAt.getTime() > 20 * 60 * 1000)
}

function positionLabel(machine, position, index) {
  return `位置 ${machine.id}${index + 1}${position.fixedPair ? '·固定组合' : ''}`
}

// [includeSkippedTurns] adds the count of skipped turns, as the registration's detail does; the
// small line of a tile leaves it out, as on the terminal (registrationAbsenceStatusLabel).
function absenceLabel(registration, includeSkippedTurns = true) {
  const skippedTurns = includeSkippedTurns && registration.temporaryAwaySkippedTurns > 0
    ? `·已轮空 ${registration.temporaryAwaySkippedTurns} 次`
    : ''
  if (registration.temporarilyAway) return `暂时离开${skippedTurns}`
  // A state set for the next turn reads with 将 until that turn has come.
  if (registration.skipsOneTurn) return `将轮空一次${skippedTurns}`
  if (registration.deferredOnce) return '将暂缓一次'
  return null
}

// For a registration that waits at [machine]: whether a preview at the other machine of the
// pair announces that it may be moved there.
function registrationIsAnnounced(registration, machine) {
  return Boolean(
    machine?.pairedMachineId &&
    registration?.pairedArrangementAnnounced &&
    registration.alsoQueuesAtPairedMachine &&
    !registration.skipsPairedMachineThisRound &&
    !registration.onlineRegistrationPendingCheckIn &&
    !absenceLabel(registration)
  )
}

// For every registration that lets others pass over it: whether that is only allowed, about to
// happen or happening now, read from the other registrations' passing_over.
const soloPassStates = computed(() => soloPassStatesByRegistrationId(machines.value))

const registrationNamesById = computed(() => {
  const names = new Map()
  for (const machine of machines.value) {
    for (const registration of [
      ...machine.playing,
      ...machine.waitingPositions.flatMap((position) => position.registrations)
    ]) {
      if (registration.registrationId) names.set(registration.registrationId, registration.displayId)
    }
  }
  return names
})

// The small line of a waiting registration at [machine] that will pass over registrations that
// play alone: 将越过“小王”, in place of its play preference. Null otherwise.
function registrationPassingText(registration, machine) {
  if (!machine || !registration?.passingOver?.length) return null
  if (machine.playing.some((playing) => (
    playing.registrationId && playing.registrationId === registration.registrationId
  ))) return null
  return soloPassTileText(
    registrationNamesById.value.get(registration.passingOver[0]),
    registration.passingOver.length
  )
}

// The sentence of the registration's menu about passing, in three parts. Where to cancel it is
// told only to the player of that registration, who can act on it (usability U10).
function detailSoloPassParts(detail) {
  if (!detail || detail.kind !== 'registration') return null
  return soloPassStatusParts(soloPassStates.value[detail.registration.registrationId], {
    tellsHowToCancel: Boolean(accountQueueRegistrationFor(detail.registration)),
    fixedPair: detail.registration.fixedPair === true
  })
}

// Where the page shows each of the player's own registrations: the account dialog names its
// position and time as the page does, including the renumbering around previews of the other
// machine (website review W4).
const queuePagePlaces = computed(() => {
  const ids = new Set(accountRegistrationIds())
  const places = {}
  for (const location of registrationLocations.value) {
    const registrationId = location.registration.registrationId
    if (!registrationId || !ids.has(registrationId)) continue
    const playing = location.kind === 'PLAYING'
    places[registrationId] = {
      machineId: location.machine.id,
      playing,
      label: location.label,
      estimate: playing ? null : location.estimate ?? null,
      estimateUncertain: !playing && location.estimateUncertain === true,
      estimateUpper: playing ? null : location.estimateUpper ?? null,
      previewPosition: pairedPreviewPosition(registrationId),
      fixedPartnerName: fixedPartnerName(location.registration),
      checkInRestarted: hasRestartedOnlineCheckInWindow(location.registration)
    }
  }
  return places
})

// Every registration the page shows, for 与朋友共同游玩 (the other registrations that wait at the
// same machine, each with its position as the page names it) and 修改登记昵称 (the nicknames in
// use) in the two action panels; see `pageRegistrations` in queueActionAvailability.js. The
// account's own queue API lists only the player's registrations.
const queuePageRegistrations = computed(() => registrationLocations.value.map((location) => ({
  registrationId: location.registration.registrationId,
  displayId: location.registration.displayId,
  machineId: location.machine.id,
  waiting: location.kind === 'WAITING',
  positionLabel: location.label,
  preference: location.registration.preference,
  fixedPair: location.registration.fixedPair === true,
  pendingCheckIn: location.registration.onlineRegistrationPendingCheckIn === true,
  deferredOnce: location.registration.deferredOnce === true,
  skipsOneTurn: location.registration.skipsOneTurn === true,
  temporarilyAway: location.registration.temporarilyAway === true
})))

// The name of the other registration of the fixed pair of [registration] (a registration of the
// public queue), or null: the account's own queue API names only the player's registration.
function fixedPartnerName(registration) {
  if (!registration?.fixedPair || !registration.fixedPairId) return null
  const partner = registrationLocations.value.find((location) => (
    location.registration.fixedPairId === registration.fixedPairId &&
    location.registration.registrationId !== registration.registrationId
  ))
  return partner?.registration.displayId || null
}

// Where the other machine of a pair shows the preview of the registration with
// [registrationId], such as B1; null when no machine shows one. Named as the terminal names it
// (maintainer, 2026-11-25: "合理引用位置编号").
function pairedPreviewPosition(registrationId) {
  if (!registrationId) return null
  for (const machine of machines.value) {
    const index = machine.waitingPositions.findIndex((position) => (
      (position.pairedPreviews || []).some((preview) => preview.registrationId === registrationId)
    ))
    if (index >= 0) return `${machine.id}${index + 1}`
  }
  return null
}

function registrationLabel(registration, machine = null) {
  return (registration.onlineRegistrationPendingCheckIn ? '线上登记·待签到' : null) ||
    absenceLabel(registration, false) ||
    // The coming move stands in place of the play preference, and ahead of an earlier no-show.
    (registrationIsAnnounced(registration, machine)
      ? pairedQueueEntryNotice(
          pairedPreviewPosition(registration.registrationId) ?? `机台 ${machine.pairedMachineId}`
        )
      : null) ||
    // Why the registration stands ahead of one that plays alone, in place of the preference.
    registrationPassingText(registration, machine) ||
    (registration.noShowCount > 0 ? `未到场 ${registration.noShowCount} 次` : null) ||
    (registration.fixedPair ? '固定组合' : null) ||
    (registration.preference === 'SOLO' ? '单人游玩' : '允许他人加入')
}

// Registration tiles follow the terminal's rule (usability U5): a tile is as wide as the longer
// of its two lines, the nickname counted up to six characters, so that a nickname of up to six
// characters never ends in an ellipsis and the small line is shown in full. Tiles that do not
// fit side by side in their position take a row each (see .queue-registration-grid).
const TILE_FONT_FAMILY = '-apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans SC", sans-serif'
const TILE_NICKNAME_FONT = `570 14px ${TILE_FONT_FAMILY}`
// The small line is set at 570 where it is coloured; measured at that weight in every case.
const TILE_SMALL_LINE_FONT = `570 10px ${TILE_FONT_FAMILY}`
// Padding 10 on the left, 28 on the right for the arrow, and the border; 10 on the right for a
// preview without an arrow.
const TILE_HORIZONTAL_SPACE = 40
const TILE_WITHOUT_ARROW_HORIZONTAL_SPACE = 22
const TILE_MIN_WIDTH = 96
const TILE_NICKNAME_CHARACTERS = 6
const tileTextWidths = new Map()
let tileMeasureContext

function tileTextWidth(text, font) {
  const key = `${font}|${text}`
  if (tileTextWidths.has(key)) return tileTextWidths.get(key)
  if (tileMeasureContext === undefined) {
    tileMeasureContext = typeof document === 'undefined'
      ? null
      : document.createElement('canvas').getContext('2d')
  }
  let width = Array.from(text).length * (font === TILE_NICKNAME_FONT ? 14 : 10)
  if (tileMeasureContext) {
    tileMeasureContext.font = font
    width = Math.ceil(tileMeasureContext.measureText(text).width)
  }
  if (tileTextWidths.size > 2000) tileTextWidths.clear()
  tileTextWidths.set(key, width)
  return width
}

// The width a tile asks for, as the CSS value of --tile-width.
function registrationTileWidth(nickname, smallLine, horizontalSpace = TILE_HORIZONTAL_SPACE) {
  const shownNickname = Array.from(String(nickname || ''))
    .slice(0, TILE_NICKNAME_CHARACTERS)
    .join('')
  const textWidth = Math.max(
    tileTextWidth(shownNickname, TILE_NICKNAME_FONT),
    tileTextWidth(String(smallLine || ''), TILE_SMALL_LINE_FONT)
  )
  return `${Math.max(TILE_MIN_WIDTH, textWidth + horizontalSpace + 1)}px`
}

function registrationTone(registration) {
  if (registration.onlineRegistrationPendingCheckIn) return 'online'
  if (
    registration.temporarilyAway ||
    registration.skipsOneTurn ||
    registration.deferredOnce
  ) return 'absence'
  if (registration.noShowCount > 0) return 'warning'
  return 'normal'
}

// Why a waiting position shows no estimate at the moment, or null when it shows one.
function positionEstimateUnavailableText(minutes, registrations = [], machine = null) {
  if (snapshotStale.value) return '数据已过期，暂时无法估算'
  if (!terminalOnline.value) return '终端恢复同步后重新估算'
  if (machine?.operational === false) return '机台恢复使用后重新估算'
  if (registrations.some((registration) => registration.temporarilyAway)) {
    return '暂时离开，无法估算'
  }
  if (minutes === null || minutes === undefined) return '暂时无法估算'
  return null
}

// [uncertain] is the terminal's mark that the estimate may be inaccurate; it then reads as the
// earliest time.
function positionEstimateText(minutes, registrations = [], machine = null, uncertain = false) {
  const unavailable = positionEstimateUnavailableText(minutes, registrations, machine)
  if (unavailable) return unavailable
  if (uncertain) return pairedQueueUncertainEstimateText(minutes)
  if (minutes <= 0) return '预计很快可以游玩'
  return `${formatWaitAmount(minutes)}后可以游玩`
}

// The wait shown for one registration: its own where the terminal sent one, otherwise that of
// its position. A position shows the earlier of its two registrations; where the earlier one may
// be arranged to the other machine of a pair, the other one does not play earlier for that, and
// the terminal sends its own wait (own_estimated_wait_minutes; review 2026-11-27). The account
// dialog keeps the same value (accountQueueEstimate).
function registrationWaitEstimate(registration, positionMinutes, positionUncertain, positionUpper = null) {
  const ownMinutes = registration?.ownEstimatedWaitMinutes
  if (ownMinutes !== null && ownMinutes !== undefined) {
    return {
      minutes: ownMinutes,
      uncertain: registration.ownEstimatedWaitUncertain === true,
      upper: registration.ownEstimatedWaitUpperMinutes ?? null
    }
  }
  return {
    minutes: positionMinutes,
    uncertain: positionUncertain === true,
    upper: positionUncertain === true ? positionUpper ?? null : null
  }
}

// The line under an uncertain wait in a detail view: the wait if the system arranges nobody
// between the two machines with its condition where the terminal sent one, otherwise the general
// note (as on the terminal).
function uncertainEstimateNote(subject, upper) {
  return noArrangementWaitNote(subject, upper) || PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE
}

// Why a position plays earlier than one ahead of it, as the terminal says it (earlierThanAheadNote):
// a registration ahead is sure to be arranged to the other machine, and this one can then pass
// and play with an earlier registration, while the page still shows the order as it stands. The
// terminal also requires a registration ahead that may be arranged away; the page uses those
// that allow it (review X-B1, 2026-11-28).
function earlierThanAheadNote(detail) {
  if (!detail || detail.isPlaying) return null
  const machine = detail.machine
  const keys = detail.kind === 'position'
    ? (detail.registrations || []).map((registration) => registration.registrationId)
    : [detail.registration?.registrationId]
  if (keys.some((key) => !key)) return null
  const minutes = detail.kind === 'position' ? detail.estimate : detail.estimatedWaitMinutes
  const registrations = detail.kind === 'position' ? detail.registrations : detail.locationRegistrations
  if (minutes === null || minutes === undefined) return null
  if (positionEstimateUnavailableText(minutes, registrations, machine) !== null) return null
  const positions = machine?.waitingPositions || []
  const index = positions.findIndex((position) => (
    position.registrations.some((registration) => keys.includes(registration.registrationId))
  ))
  if (index <= 0) return null
  const ahead = positions.slice(0, index)
  const moveAhead = ahead.some((position) => position.registrations.some((registration) => (
    registration.alsoQueuesAtPairedMachine && !registration.skipsPairedMachineThisRound
  )))
  if (!moveAhead) return null
  if (!ahead.some((position) => (position.estimatedWaitMinutes ?? -1) > minutes)) return null
  return detail.kind === 'position'
    ? '受系统在两台机台之间安排登记的影响，这个位置预计早于前方的部分位置游玩。'
    : '受系统在两台机台之间安排登记的影响，这份登记预计早于前方的部分登记游玩。'
}

// Whether the estimate shown is one marked as possibly inaccurate. A detail view then adds
// PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE once; list cells leave the note out.
function positionEstimateUncertain(minutes, registrations = [], machine = null, uncertain = false) {
  return uncertain === true &&
    positionEstimateUnavailableText(minutes, registrations, machine) === null
}

// The earlier time for when the pending online registrations ahead do not check in, for a
// whole position: only when every registration of it would play earlier.
function positionOnlineMissingNote(detail) {
  if (detail.isPlaying) return null
  if (positionEstimateUnavailableText(detail.estimate, detail.registrations, detail.machine) !== null) return null
  const minutes = (detail.registrations || []).map((registration) => registration.waitMinutesIfOnlineMissing)
  if (!minutes.length || minutes.some((value) => value === null || value === undefined)) return null
  return onlineMissingWaitNote('这个位置', Math.min(...minutes))
}

// The same for one registration.
function registrationOnlineMissingNote(detail) {
  if (positionEstimateUnavailableText(detail.estimatedWaitMinutes, detail.locationRegistrations, detail.machine) !== null) return null
  return onlineMissingWaitNote('这份登记', detail.registration?.waitMinutesIfOnlineMissing)
}

function registrationTypeLabel(registration) {
  return registration.registrationType === 'PLAYER_PROFILE' ? '玩家资料登记' : '临时登记'
}

function preferenceLabel(registration) {
  if (registration.fixedPair) return '与朋友共同游玩'
  return registration.preference === 'SOLO' ? '单人游玩' : '允许他人加入'
}

// The pill of a registration's detail. A member of a fixed pair is labelled 固定组合, as its
// block on the terminal; the partner pill beside it names the friend, so 与朋友共同游玩 would say
// the same thing twice.
function preferencePillLabel(registration) {
  return registration.fixedPair ? '固定组合' : preferenceLabel(registration)
}

function registrationPartnerText(detail) {
  if (!detail || detail.kind !== 'registration') return null
  const partners = detail.locationRegistrations.filter((registration) => (
    registration.registrationId !== detail.registration.registrationId
  ))
  if (partners.length) {
    const names = partners.map((partner) => `“${partner.displayId}”`).join('、')
    // A fixed pair plays together by choice: the terminal writes 与“X”共同游玩 (G13).
    if (detail.registration.fixedPair) return `与${names}共同游玩`
    const action = detail.isPlaying ? '正在与' : '将与'
    return `${action}${names}共同游玩`
  }
  if (detail.commonPlayPreview) {
    return `预计与“${detail.commonPlayPreview.displayId}”共同游玩`
  }
  return null
}

function noShowResultLabel(registration) {
  if (!registration.noShowCount) return null
  return registration.lastNoShowActionWasDefer
    ? `未到场 ${registration.noShowCount} 次，上次为暂缓一次`
    : `未到场 ${registration.noShowCount} 次，上次为移至队尾`
}

// When a registration was created or last played, as the terminal's registration menu writes
// it (formatRegistrationTime): 刚刚, “5 分钟前” up to half an hour, then the clock time, with the
// date from a day on.
function registrationTimeText(value, fallback) {
  const date = parseDate(value)
  if (!date) return fallback
  const difference = Math.max(0, currentTime.value - date.getTime())
  if (difference < 60000) return '刚刚'
  if (difference <= 30 * 60000) return `${Math.floor(difference / 60000)} 分钟前`
  const clock = pairedQueueClockTime(date.getTime())
  return difference < 24 * 60 * 60000
    ? clock
    : `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${clock}`
}

function fullTimeText(value, fallback = '尚无记录') {
  const date = parseDate(value)
  if (!date) return fallback
  return date.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

function openPosition(machine, position = null) {
  const isPlaying = position === null
  const registrations = isPlaying ? machine.playing : position.registrations
  if (!registrations.length) return
  selectedDetail.value = {
    kind: 'position',
    title: isPlaying ? `游玩位置 ${machine.id}` : positionLabel(machine, position, position.index),
    machine,
    registrations,
    isPlaying,
    estimate: isPlaying ? null : position.estimatedWaitMinutes,
    estimateUncertain: !isPlaying && position.estimatedWaitUncertain === true,
    estimateUpper: isPlaying ? null : position.estimatedWaitUpperMinutes ?? null,
    playingText: isPlaying ? playingLabel(machine) : null,
    positionId: isPlaying ? null : position.positionId,
    commonPlayPreview: isPlaying ? null : position.commonPlayPreview
  }
}

function openMachineDetails(machine) {
  selectedDetail.value = {
    kind: 'machine',
    title: machine.name,
    machine
  }
}

function openPairedPreview(machine, preview) {
  if (!preview) return
  selectedDetail.value = {
    kind: 'pairedPreview',
    title: PAIRED_QUEUE_PREVIEW_TITLE,
    machine,
    registration: preview
  }
}

// A machine by its configured name (备注·机台 B), as the terminal names the machines in these
// sentences and as the account dialog does; the registration tiles keep the short 机台 B.
function machineNameById(machineId) {
  return machines.value.find((machine) => machine.id === machineId)?.name || `机台 ${machineId}`
}

function pairedPreviewExplanation(detail) {
  return pairedQueuePreviewExplanation({
    homeMachineName: machineNameById(detail.registration.homeMachineId),
    previewMachineName: detail.machine.name
  })
}

// What the detail of a registration says while a preview at the other machine announces a
// move. The player's own registration says the same inside its status line instead.
function detailArrangementSentence(detail) {
  if (!detail || detail.kind !== 'registration' || detail.isPlaying) return ''
  if (!registrationIsAnnounced(detail.registration, detail.machine)) return ''
  if (accountPairedQueueStatusParts(accountQueueRegistrationFor(detail.registration))) return ''
  return pairedQueueAnnouncementSentence({
    pairedMachineName: machineNameById(detail.machine.pairedMachineId),
    notBeforeMillis: detail.registration.pairedArrangementNotBefore,
    nowMillis: currentTime.value,
    previewPosition: pairedPreviewPosition(detail.registration.registrationId)
  })
}

function resetDetailAction() {
  detailActionMode.value = null
  detailActionTargetMachineId.value = ''
  detailActionPreference.value = 'SOLO'
  detailActionError.value = ''
  detailActionNotice.value = ''
}

function accountQueueRegistrationFor(registration) {
  if (!registration?.registrationId) return null
  return playerAccountQueueState.value?.registrations?.find((candidate) => (
    candidate.registration_id === registration.registrationId
  )) || null
}

// The name of the machine that forms a pair with the registration's machine, if there is one.
function accountPairedMachineName(registration) {
  const pairedMachineId = registration?.paired_machine_id
  if (!pairedMachineId) return null
  return playerAccountQueueState.value?.queue?.machines?.find((machine) => (
    machine.id === pairedMachineId
  ))?.name || null
}

// One small line on the player's own registration whose tick box is on: what the system may
// do with it, and where the box can be cleared again. It also says so while the system leaves
// the registration at its own machine until it has played a round.
function accountPairedQueueStatusParts(registration) {
  if (!registration?.also_queues_at_paired_machine) return null
  const pairedMachineName = accountPairedMachineName(registration)
  if (!pairedMachineName) return null
  return pairedQueueStatusParts({
    pairedMachineName,
    skipsThisRound: registration.skips_paired_machine_this_round === true,
    announced: registration.paired_arrangement_announced === true,
    notBeforeMillis: registration.paired_arrangement_not_before ?? null,
    nowMillis: currentTime.value,
    previewPosition: pairedPreviewPosition(registration.registration_id)
  })
}

function detailActionPrompt(registration, mode) {
  if (!registration) return null
  const postponement = postponeConfirmation(registration, mode)
  if (postponement) return postponement
  if (mode === 'leave') return leaveConfirmation(registration)
  return null
}

function detailTransferPrompt(registration) {
  const target = playerAccountQueueState.value?.queue?.machines?.find((machine) => (
    machine.id === detailActionTargetMachineId.value
  ))
  if (!target) return null
  // The tick box is on: say what a transfer by hand means for it.
  const pairedMachineName = registration.also_queues_at_paired_machine
    ? accountPairedMachineName(registration)
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

// The time limit of a pending online registration in the detail, in the terminal's words; the
// player's own registration adds where and how to check in.
function detailCheckInLimitText(detail) {
  return onlineCheckInLimitText({
    restarted: hasRestartedOnlineCheckInWindow(detail.registration),
    tellsHowToCheckIn: isMarkedRegistration(detail.registration)
  })
}

// The machines of 选择要转至的机台, each with what waits there and the wait after moving, or
// the reason why it cannot take the registration.
function detailTransferRows(registration) {
  return transferChooserRows(registration, detailOtherMachines(registration), waitingPositionCounts.value)
}

function chooseDetailTransferTarget(row) {
  if (row.reason || detailActionSubmitting.value) return
  detailActionTargetMachineId.value = row.id
}

// Why the entry for moving machines is grey, or an empty string.
function detailTransferUnavailableReason(registration) {
  return detailActionUnavailableReason(registration, 'transfer') ||
    transferEntryUnavailableReason(registration, detailOtherMachines(registration))
}

// With only one other machine there is nothing to choose: the confirmation opens at once, as
// on the terminal. A machine that cannot take the registration says so before (website review
// W5), as the terminal's grey entry does.
function requestDetailTransfer(registration) {
  const reason = transferEntryUnavailableReason(registration, detailOtherMachines(registration))
  if (reason && !detailActionUnavailableReason(registration, 'transfer')) {
    detailActionError.value = reason
    detailActionNotice.value = ''
    return
  }
  const others = detailOtherMachines(registration)
  detailActionTargetMachineId.value = others.length === 1 ? others[0].id : ''
  requestDetailAction('transfer')
}

// Why the chosen machine cannot take the registration, should that have changed since the
// confirmation opened; the confirmation is then grey.
function detailTransferTargetReason(registration) {
  const target = playerAccountQueueState.value?.queue?.machines?.find((machine) => (
    machine.id === detailActionTargetMachineId.value
  ))
  return transferTargetUnavailableReason(registration, target)
}

function submitDetailTransfer(registration) {
  const reason = detailTransferTargetReason(registration)
  if (reason) {
    detailActionError.value = reason
    detailActionNotice.value = ''
    return
  }
  submitDetailQueueAction(registration, 'TRANSFER_MACHINE', {
    target_machine_id: detailActionTargetMachineId.value,
    expected_target_machine_stable_id: playerAccountQueueState.value?.queue?.machines?.find((machine) => (
      machine.id === detailActionTargetMachineId.value
    ))?.stable_id
  })
}

function detailAccountCsrfHeaders() {
  if (typeof document === 'undefined') return {}
  const cookie = document.cookie.split(';').map((part) => part.trim()).find((part) => (
    part.startsWith('maimai_q_session_csrf=')
  ))
  if (!cookie) return {}
  try {
    return { 'X-CSRF-Token': decodeURIComponent(cookie.slice('maimai_q_session_csrf='.length)) }
  } catch {
    return {}
  }
}

// Every command on the player's own registration runs through queueCommandChain.js, which
// carries on when the detail is closed (website review W3).
function submitDetailQueueCommands(accountRegistration, commands, pairedMachineName = null) {
  const state = playerAccountQueueState.value
  if (!accountRegistration || !state?.queue || detailActionSubmitting.value || !commands?.length) return
  const unavailableReason = detailActionUnavailableReason(
    accountRegistration,
    QUEUE_ACTION_MODE_BY_OPERATION[commands[0].operation]
  )
  if (unavailableReason) {
    detailActionError.value = unavailableReason
    detailActionNotice.value = ''
    return
  }
  detailActionError.value = ''
  detailActionNotice.value = QUEUE_COMMAND_SUBMITTED_NOTICE
  runQueueCommands({
    source: 'registration-detail',
    registration: accountRegistration,
    state,
    commands,
    pairedMachineName,
    queueOperations: capabilities.value?.queue_operations,
    accountApiUrl: PLAYER_ACCOUNT_API_URL,
    commandApiBase: QUEUE_ONLINE_COMMAND_API_BASE,
    csrfHeaders: detailAccountCsrfHeaders
  })
}

function submitDetailQueueAction(accountRegistration, operation, extra = {}) {
  submitDetailQueueCommands(accountRegistration, [{ operation, extra }])
}

// The progress line of the open detail while a command on its registration runs, also when the
// detail was opened after the command was sent.
// While a request without an answer is sent again, the line says so and why nothing else can be
// sent (website review W-L7).
const detailActionNoticeText = computed(() => {
  const run = queueCommandRun.value
  const ownRun = run && selectedDetail.value?.kind === 'registration' &&
    selectedDetail.value.registration.registrationId === run.registrationId
  if (ownRun && run.confirming) return queueCommandRunNotice()
  if (detailActionNotice.value) return detailActionNotice.value
  return ownRun ? queueCommandRunNotice() : ''
})

// The outcome of a run on the player's own registration. The banner tells it unless the account
// dialog that started it is still open and tells it there; the open detail of the registration
// shows what was applied beside what was not.
watch(queueCommandOutcome, (outcome) => {
  if (!outcome) return
  const detail = selectedDetail.value
  if (detail?.kind === 'registration' && detail.registration.registrationId === outcome.registrationId) {
    detailActionMode.value = null
    detailActionNotice.value = outcome.notice
    detailActionError.value = outcome.error
  }
  if (outcome.source !== 'account-dialog' || !playerAccountDialogVisible.value) {
    if (outcome.kind === 'error') {
      addOperationFeedback('error', outcome.title, `${outcome.notice}${outcome.error}请刷新最新状态后重新确认。`, true)
    } else if (outcome.kind === 'unknown') {
      // The command may have been carried out: the banner says how to check, with 刷新最新状态.
      addOperationFeedback('unknown', outcome.title, `${outcome.notice}${outcome.error}`, true)
    } else {
      addOperationFeedback(outcome.kind, outcome.title, outcome.notice)
    }
  }
  if (outcome.loginRequired) {
    handlePlayerAccountSession(null)
  } else {
    refreshLoggedInPlayerQueue()
  }
  loadQueue(true)
})

// The preference panel holds the play preference of the registration and, where its machine
// has a paired machine, the tick box that lets the system arrange it between the two.
function openDetailPreferenceAction(accountRegistration) {
  if (!accountRegistration) return
  detailActionPreference.value = initialPreferenceChoice(accountRegistration)
  detailActionPairedQueue.value = accountRegistration.also_queues_at_paired_machine === true
  const passingChoices = initialPassingChoices(accountRegistration)
  detailActionPassing.value = passingChoices.passing
  detailActionPairPassing.value = passingChoices.pairPassing
  requestDetailAction('preference')
}

// The tick box that lets others pass is offered inside 单人游玩 where the preference can be
// chosen and the terminal knows the choice.
function detailPassingOffered(accountRegistration) {
  return accountRegistration?.machine_capacity > 1 &&
    terminalSupportsQueueOperation(capabilities.value, 'SET_ALLOWS_PASSING')
}

// A member of a fixed pair is offered the pair's tick box where the terminal lets a fixed pair
// allow passing.
function detailPairPassingOffered(accountRegistration) {
  return accountRegistration?.fixed_pair === true && detailPassingOffered(accountRegistration) &&
    terminalSupportsFixedPairPassing(capabilities.value)
}

// A new fixed pair is asked whether it lets others pass where the terminal lets a fixed pair
// allow passing, as on the terminal's confirmation.
function detailNewPairPassingOffered(accountRegistration) {
  return detailPassingOffered(accountRegistration) &&
    terminalSupportsFixedPairPassing(capabilities.value)
}

// 与朋友共同游玩, the row under 也可以 in 更改游玩偏好: offered where the terminal has declared the
// operation, greyed with its reason.
function detailFriendPairOffered(accountRegistration) {
  return friendPairOffered(accountRegistration, capabilities.value?.queue_operations)
}

function detailFriendPairEntry(accountRegistration) {
  return friendPairEntry(accountRegistration, queuePageRegistrations.value)
}

function requestDetailFriendPair(accountRegistration) {
  if (detailFriendPairEntry(accountRegistration).reason) return
  requestDetailAction('friend_pair')
}

function submitDetailFriendPair(accountRegistration, { partnerRegistrationId, allowsPassing }) {
  submitDetailQueueAction(accountRegistration, 'CREATE_FIXED_PAIR', {
    partner_registration_id: partnerRegistrationId,
    // Sent, ticked or not, wherever the pair's box was offered.
    ...(allowsPassing === null ? {} : { allows_passing: allowsPassing })
  })
}

// 修改登记昵称: the pencil beside the heading of the player's own registration, as on the
// terminal's registration menu.
function detailRenameOffered(detail) {
  if (!detail || detail.kind !== 'registration' || !accountSessionActive.value) return false
  return registrationRenameOffered(
    accountQueueRegistrationFor(detail.registration),
    capabilities.value?.queue_operations
  )
}

// The form opens among the registration's actions, further down in the detail.
function openDetailRename() {
  requestDetailAction('rename')
  nextTick(() => {
    document.querySelector('.queue-detail-account-actions')?.scrollIntoView({ block: 'nearest' })
  })
}

function submitDetailRename(accountRegistration, displayId) {
  submitDetailQueueAction(accountRegistration, 'RENAME_REGISTRATION', { display_id: displayId })
}

function detailPairPassingChanged(accountRegistration) {
  return fixedPairPassingChanged({
    registration: accountRegistration,
    preference: detailActionPreference.value,
    pairPassing: detailPairPassingOffered(accountRegistration) ? detailActionPairPassing.value : null
  })
}

function detailPairedQueueChanged(accountRegistration) {
  return Boolean(accountPairedMachineName(accountRegistration)) &&
    detailActionPairedQueue.value !== (accountRegistration.also_queues_at_paired_machine === true)
}

// On a machine for one player the panel holds nothing but the tick box. A member of a fixed
// pair opens the panel with neither preference chosen; until it chooses one, only a change of
// a tick box can be confirmed: the one for the two machines, or the pair's own.
function detailPreferenceChangeIsEmpty(accountRegistration) {
  if (!accountRegistration) return true
  const preferenceChosen = accountRegistration.machine_capacity > 1 &&
    Boolean(detailActionPreference.value)
  return !preferenceChosen && !detailPairedQueueChanged(accountRegistration) &&
    !detailPairPassingChanged(accountRegistration)
}

// One confirmation, up to three commands for the terminal: the play preference and the two tick
// boxes, each sent once the one before has been applied (preferenceChangeCommands).
function submitDetailPreferenceChange(accountRegistration) {
  const pairedMachineName = accountPairedMachineName(accountRegistration)
  submitDetailQueueCommands(accountRegistration, preferenceChangeCommands({
    registration: accountRegistration,
    preference: detailActionPreference.value,
    pairedTick: pairedMachineName ? detailActionPairedQueue.value : null,
    passing: detailPassingOffered(accountRegistration) ? detailActionPassing.value : null,
    pairPassing: detailPairPassingOffered(accountRegistration) ? detailActionPairPassing.value : null
  }), pairedMachineName)
}

// A member of a fixed pair that has chosen a preference releases the pair: the terminal asks a
// second question first, and so does the panel. Otherwise the change is sent at once.
function confirmDetailPreferenceChange(accountRegistration) {
  if (preferenceChangeReleasesFixedPair(accountRegistration, detailActionPreference.value)) {
    openDetailAction('release_pair')
    return
  }
  submitDetailPreferenceChange(accountRegistration)
}

function detailReleasePairPrompt(accountRegistration) {
  return releaseFixedPairConfirmation(
    accountRegistration,
    detailActionPreference.value,
    fixedPartnerName(selectedDetail.value?.registration)
  )
}

function openDetailAction(mode) {
  detailActionMode.value = mode
  detailActionError.value = ''
  detailActionNotice.value = ''
}

// Cancelling 暂缓一次, 轮空一次 or 暂时离开 takes effect at once, as on the terminal.
function cancelDetailPostponement(mode) {
  const registration = selectedDetail.value?.kind === 'registration'
    ? accountQueueRegistrationFor(selectedDetail.value.registration)
    : null
  const unavailableReason = detailActionUnavailableReason(registration, mode)
  if (unavailableReason) {
    detailActionError.value = unavailableReason
    detailActionNotice.value = ''
    return
  }
  detailActionError.value = ''
  detailActionNotice.value = ''
  submitDetailQueueAction(registration, POSTPONEMENT_CANCEL_OPERATIONS[mode], {})
}

function detailOtherMachines(registration) {
  return playerAccountQueueState.value?.queue?.machines?.filter(
    (machine) => machine.id !== registration?.machine_id
  ) || []
}

function requestDetailAction(mode) {
  const registration = selectedDetail.value?.kind === 'registration'
    ? accountQueueRegistrationFor(selectedDetail.value.registration)
    : null
  const unavailableReason = detailActionUnavailableReason(registration, mode)
  if (unavailableReason) {
    detailActionError.value = unavailableReason
    detailActionNotice.value = ''
    return
  }
  openDetailAction(mode)
}

// Why the action of this mode cannot be carried out for the registration right now; empty
// when it can.
function detailActionUnavailableReason(registration, mode) {
  const state = playerAccountQueueState.value
  return queueActionUnavailableReason({
    terminalOnline: Boolean(state?.queue?.terminal_online),
    remoteActions: Boolean(state?.queue?.remote_actions),
    registration,
    mode,
    queueOperations: capabilities.value?.queue_operations
  })
}

function openRegistration(
  machine,
  registration,
  locationLabel,
  estimatedWaitMinutes = null,
  estimateUncertain = false,
  locationRegistrations = [],
  commonPlayPreview = null,
  isPlaying = false,
  preserveAction = false
) {
  if (!preserveAction) resetDetailAction()
  // The detail of a waiting registration tells that registration's wait, which may be later
  // than the one its position shows.
  // The position's second time comes from where the page lists the registration.
  const positionUpper = isPlaying
    ? null
    : registrationLocations.value.find((location) => location.registration === registration)?.estimateUpper ?? null
  const estimate = isPlaying
    ? { minutes: estimatedWaitMinutes, uncertain: estimateUncertain === true, upper: null }
    : registrationWaitEstimate(registration, estimatedWaitMinutes, estimateUncertain, positionUpper)
  selectedDetail.value = {
    kind: 'registration',
    title: registration.displayId,
    machine,
    locationLabel,
    estimatedWaitMinutes: estimate.minutes,
    estimateUncertain: estimate.uncertain,
    estimateUpper: estimate.upper,
    locationRegistrations,
    commonPlayPreview,
    isPlaying,
    registration
  }
}

function openRegistrationFromPosition(registration) {
  const detail = selectedDetail.value
  if (!detail || detail.kind !== 'position') return
  openRegistration(
    detail.machine,
    registration,
    compactMiddleDots(detail.title).replace('·固定组合', ''),
    detail.isPlaying ? null : detail.estimate,
    !detail.isPlaying && detail.estimateUncertain,
    detail.registrations,
    detail.commonPlayPreview,
    detail.isPlaying
  )
}

function closeDetail() {
  resetDetailAction()
  selectedDetail.value = null
}

function markedSelfStatusTitle() {
  const location = markedSelfLocation.value
  const identity = activeSelfIdentity.value
  if (!terminalOnline.value) return '队列状态等待更新'
  if (!location) {
    if (markedSelfAmbiguous.value) {
      return accountSessionActive.value ? '当前账户有多份登记' : '发现多份同名登记'
    }
    if (accountSessionActive.value) return '当前队列中没有本人的登记'
    if (identity?.queueId !== queueId.value) return '当前队列中还没有本人的登记'
    if (markedSelfLastEvent.value) return eventOutcomeTitle(markedSelfLastEvent.value)
    return '本人的登记当前不在队列中'
  }
  const registration = location.registration
  if (!location.machine.operational) return `${location.machine.name} 已停止使用`
  if (registration.onlineRegistrationPendingCheckIn) return '线上登记仍待现场签到'
  if (registration.temporarilyAway) return '当前处于暂时离开状态'
  if (registration.skipsOneTurn) return '将轮空一次'
  if (registration.deferredOnce) return '将暂缓一次'
  if (location.kind === 'PLAYING') return '现在轮到本人游玩'
  return positionEstimateText(
    location.estimate,
    location.registrations,
    location.machine,
    location.estimateUncertain
  )
}

function markedSelfStatusDetail() {
  const location = markedSelfLocation.value
  if (!terminalOnline.value) {
    return location
      ? `最后一次同步时，本人的登记在${location.label}。终端恢复同步后，请再确认当前安排。`
      : `${snapshotStale.value ? '队列长时间没有更新' : '现场终端暂时离线'}，暂时无法确认本人的当前状态。${accountSessionActive.value ? '恢复同步后会重新显示本人的登记。' : '标记会继续保留。'}`
  }
  if (!location) {
    if (markedSelfAmbiguous.value) {
      return accountSessionActive.value
        ? '当前账户关联到多份登记，网页不会自动选择其中一份。请在现场终端处理。'
        : `当前有多份昵称为“${markedSelf.value?.displayId}”的登记。请打开属于本人的登记，并再次选择“标记为自己”。`
    }
    if (accountSessionActive.value) return '当前账户在这个队列中没有登记。'
    if (markedSelf.value?.queueId !== queueId.value) {
      return '标记会继续保留；使用相同昵称加入当前队列后，位置和预计时间会自动恢复更新。'
    }
    const event = markedSelfLastEvent.value
    if (event) {
      return `${event.detail} 标记会继续保留；使用相同昵称再次加入排队后，位置和预计时间会自动恢复更新。`
    }
    return '标记会继续保留；使用相同昵称再次加入排队后，位置和预计时间会自动恢复更新。'
  }
  const registration = location.registration
  if (!location.machine.operational) {
    const playingTimerNotice = location.machine.playing.length
      ? '本轮游玩计时会在恢复正常使用后从头开始。'
      : ''
    const checkInTimerNotice = registration.onlineRegistrationPendingCheckIn
      ? '机台停止使用期间，30 分钟签到计时暂停；恢复正常使用后会从头开始。'
      : ''
    return `停止原因：${stopReasonLabel(
      location.machine.stopReason,
      location.machine.stopReasonDetail
    )}。登记顺序仍然保留。${playingTimerNotice}${checkInTimerNotice}`
  }
  if (registration.onlineRegistrationPendingCheckIn) {
    return onlineCheckInLimitText({
      restarted: hasRestartedOnlineCheckInWindow(registration),
      tellsHowToCheckIn: true
    })
  }
  if (registration.temporarilyAway) {
    const skipped = registration.temporaryAwaySkippedTurns
    return skipped > 0
      ? `这份登记每次轮到时都会移至队尾，当前已轮空 ${skipped} 次；第 4 次轮到时仍未取消暂时离开，登记会自动移除。`
      : '这份登记每次轮到时都会移至队尾；取消暂时离开后，才能再次进入游玩位置。'
  }
  if (registration.skipsOneTurn) {
    return [SKIP_ONE_TURN_EXPLANATION, skipOneTurnLimitNotice(registration.temporaryAwaySkippedTurns)]
      .filter(Boolean)
      .join('')
  }
  if (registration.deferredOnce) return '下一次轮到这份登记时不会进入游玩位置。这份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。'
  if (location.kind === 'PLAYING') return markedSelfPartnerText.value
  return markedSelfPartnerText.value
}

function markedSelfTone() {
  const location = markedSelfLocation.value
  if (!terminalOnline.value) return 'warning'
  if (!location) {
    if (markedSelfAmbiguous.value) return 'warning'
    return markedSelfLastEvent.value?.type.startsWith('NO_SHOW') ||
      markedSelfLastEvent.value?.type === 'TEMPORARY_AWAY_EXPIRED' ||
      markedSelfLastEvent.value?.type === 'ONLINE_CHECK_IN_TIMED_OUT' ||
      markedSelfLastEvent.value?.type === 'ONLINE_CHECK_IN_MISSED'
      ? 'danger'
      : 'muted'
  }
  if (location.registration.onlineRegistrationPendingCheckIn) return 'online'
  if (location.registration.noShowCount > 0) return 'danger'
  if (!location.machine.operational || location.registration.temporarilyAway ||
    location.registration.skipsOneTurn || location.registration.deferredOnce) return 'warning'
  return location.kind === 'PLAYING' ? 'playing' : 'normal'
}

function eventOutcomeTitle(event) {
  const labels = {
    NO_SHOW_DEFERRED: '已被标记为未到场，并已暂缓一次',
    NO_SHOW_MOVED_TO_TAIL: '已被标记为未到场，并已移至队尾',
    NO_SHOW_REMOVED: '已被标记为未到场，登记已被移除',
    TEMPORARY_AWAY_EXPIRED: '暂时离开期间第 4 次轮到，登记已自动移除',
    ONLINE_REGISTRATION_ADDED: '线上登记已经创建，请按时到现场签到',
    ONLINE_CHECK_IN_COMPLETED: '已完成现场签到',
    ONLINE_CHECK_IN_TIMED_OUT: '超过 30 分钟未签到，登记已自动移除',
    ONLINE_CHECK_IN_MISSED: '轮到进入游玩位置时未签到，登记已自动移除',
    REGISTRATION_REMOVED: '这份登记已不在队列中',
    REGISTRATION_CLOSED: '登记排队已经关闭',
    QUEUE_RESET: '现场已经开始新的队列'
  }
  return labels[event.type] || event.title
}

function eventTypeLabel(type) {
  const labels = {
    REGISTRATION_ADDED: '创建登记',
    REGISTRATION_REMOVED: '移除登记',
    REGISTRATION_UPDATED: '登记变动',
    QUEUE_REORDERED: '顺序调整',
    PLAYING_CHANGED: '游玩位置',
     NO_SHOW_DEFERRED: '未到场·暂缓一次',
     NO_SHOW_MOVED_TO_TAIL: '未到场·移至队尾',
     NO_SHOW_REMOVED: '未到场·移除登记',
    TEMPORARY_AWAY_EXPIRED: '暂时离开·自动移除',
    ONLINE_REGISTRATION_ADDED: '线上登记',
    ONLINE_CHECK_IN_COMPLETED: '现场签到',
    ONLINE_CHECK_IN_TIMED_OUT: '签到超时',
    ONLINE_CHECK_IN_MISSED: '轮到时未签到',
    ABSENCE_CHANGED: '暂缓一次、轮空一次或暂时离开',
    MACHINE_STOPPED: '机台停止使用',
    MACHINE_RESTORED: '机台恢复使用',
    REGISTRATION_OPENED: '开放登记',
    REGISTRATION_CLOSED: '关闭登记',
    QUEUE_RESTORED: '恢复队列',
    QUEUE_RESET: '新队列',
    OTHER: '队列变动'
  }
  return labels[type] || '队列变动'
}

function logMachineLabel(event) {
  if (!event.machineId) return '系统'
  if (event.machineStableId) {
    const currentMachine = machines.value.find(
      (machine) => machine.stableId === event.machineStableId
    )
    if (currentMachine) return currentMachine.name
    if (event.machineName) return `${event.machineName}（已删除）`
    return '已删除的机台'
  }
  return machines.value.find((machine) => machine.id === event.machineId)?.name ||
    event.machineName ||
    `机台 ${event.machineId}`
}

function operationSourceLabel(source) {
  return {
    ON_SITE_TERMINAL: '现场终端',
    QQ_BOT: 'QQ Bot',
    SYSTEM_AUTOMATIC: '系统自动',
    WEBSITE_REMOTE: '网页端',
    MOBILE_DEVICE: '移动设备'
  }[source] || '现场终端'
}

function eventIsMarkedSelf(event) {
  const registrationIds = knownSelfRegistrationIds()
  return registrationIds.length > 0 && event.registrationIds.some((registrationId) => (
    registrationIds.includes(registrationId)
  ))
}

function createRequestId() {
  if (typeof window.crypto?.randomUUID === 'function') return window.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  window.crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const value = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`
}

function normalizeOnlineMachine(source) {
  const id = String(source?.id || '').toUpperCase()
  const name = formatMiddleDots(String(source?.name || `机台 ${id}`))
  const configuration = normalizeMachineConfiguration(source, { id, name })
  const registrationCount = toNonNegativeInteger(
    source?.registration_count ?? source?.registrationCount
  ) ?? 0
  const operational = source?.operational !== false
  const available = source?.available === true && operational && registrationCount < 20
  return {
    id,
    stableId: normalizeInternalId(source?.stable_id ?? source?.stableId) ||
      machines.value.find((machine) => machine.id === id)?.stableId ||
      defaultMachineStableId(id),
    groupId: normalizeInternalId(source?.group_id ?? source?.groupId) ||
      machines.value.find((machine) => machine.id === id)?.groupId ||
      DEFAULT_MACHINE_GROUP_ID,
    name,
    configuration,
    capacity: configuration.capacity,
    operational,
    registrationCount,
    estimatedWaitMinutes: toNonNegativeInteger(
      source?.estimated_wait_minutes ?? source?.estimatedWaitMinutes
    ),
    available,
    unavailableReason: source?.unavailable_reason ?? source?.unavailableReason ??
      (!operational ? '机台已停止使用' : registrationCount >= 20 ? '登记已满' : null),
    pairedMachineId: normalizePairedMachineId(
      source?.paired_machine_id ?? source?.pairedMachineId
    )
  }
}

function normalizeOnlineGroups(data, remoteMachines) {
  const source = data?.machine_groups ?? data?.machineGroups
  const groups = []
  const seenIds = new Set()
  if (Array.isArray(source)) {
    source.forEach((group) => {
      const id = normalizeInternalId(group?.id)
      const name = String(group?.name || '').trim()
      if (id && name && !seenIds.has(id)) {
        groups.push({ id, name: name.slice(0, 12) })
        seenIds.add(id)
      }
    })
  }
  if (!groups.length) {
    return configuredMachineGroups.value.map(({ id, name }) => ({ id, name }))
  }
  const usedIds = new Set(remoteMachines.map((machine) => machine.groupId))
  return groups.filter((group) => usedIds.has(group.id))
}

function normalizeOnlineProfile(source) {
  if (!source || typeof source !== 'object') return null
  const qqNumber = normalizeQqNumber(source.qq_number ?? source.qqNumber)
  const nickname = String(source.nickname || '').trim()
  const defaultPreference = String(
    source.default_preference ?? source.defaultPreference ?? ''
  ).toUpperCase()
  if (!qqNumber || !nickname || !['SOLO', 'OPEN_TO_JOIN', 'ASK_EVERY_TIME'].includes(defaultPreference)) {
    return null
  }
  return {
    profileId: source.profile_id ?? source.profileId ?? null,
    publicPlayerId: source.public_player_id ?? source.publicPlayerId ?? null,
    nickname,
    qqNumber,
    gender: String(source.gender || 'UNDISCLOSED').toUpperCase(),
    defaultPreference,
    // Whether this profile's player has already been shown the explanation of the tick box.
    pairedQueueExplanationShown: source.paired_machine_queueing_introduced === true,
    // The same for the explanation of letting others pass.
    passingExplanationShown: source.passing_introduced === true,
    // The player's last answer to the passing tick box; the box starts from it.
    lastAllowsPassing: source.last_allows_passing === true,
    setupComplete: Number(source.setup_version ?? source.setupVersion ?? 0) >= 1
  }
}

async function readJsonResponse(response) {
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    const error = new Error(data?.error || `服务器暂时无法处理请求（HTTP ${response.status}），请稍后重试。`)
    error.code = data?.code || null
    error.status = response.status
    throw error
  }
  return data || {}
}

function firstAvailableOnlineMachineId() {
  return onlineJoinMachineOptions.value.find((machine) => machine.available)?.id ||
    onlineJoinMachineOptions.value[0]?.id || machines.value[0]?.id || 'A'
}

function resetOnlineJoin(audience = 'OTHER') {
  if (onlineCommandTimer) window.clearTimeout(onlineCommandTimer)
  onlineCommandTimer = null
  onlineJoinStep.value = 'LOOKUP'
  onlineJoinAudience.value = audience
  onlineJoinQq.value = ''
  onlineJoinMachineId.value = firstAvailableOnlineMachineId()
  onlineJoinProfile.value = null
  onlineJoinMachines.value = []
  onlineJoinGroups.value = []
  onlineJoinExistingRegistration.value = null
  onlineJoinPreference.value = null
  onlineJoinPairedAnswer.value = null
  onlineJoinPassingAnswer.value = null
  onlineJoinClosingWarning.value = null
  onlineJoinClosingAcknowledgedMachineIds.value = []
  onlineJoinLoading.value = false
  onlineJoinError.value = ''
  onlineJoinCommandId.value = null
  onlineJoinQueueId.value = null
  onlineJoinMachineConfigurationRevision.value = null
  onlineJoinResultDetail.value = ''
  onlineJoinResultRegistrationId.value = null
  onlineJoinTerminalApplied.value = false
  onlineJoinConfirming.value = false
  onlineJoinPendingRequest = null
  onlineJoinPollFailures = 0
}

function invalidateOnlineJoinConfirmation(message) {
  if (!['CONFIRM', 'EXISTING'].includes(onlineJoinStep.value)) return
  onlineJoinClosingWarning.value = null
  onlineJoinStep.value = onlineJoinAudience.value === 'SELF' ? 'SELF_ERROR' : 'LOOKUP'
  onlineJoinProfile.value = null
  onlineJoinMachines.value = []
  onlineJoinGroups.value = []
  onlineJoinExistingRegistration.value = null
  onlineJoinPreference.value = null
  onlineJoinCommandId.value = null
  onlineJoinQueueId.value = null
  onlineJoinMachineConfigurationRevision.value = null
  onlineJoinResultRegistrationId.value = null
  onlineJoinTerminalApplied.value = false
  onlineJoinError.value = message
}

function hasRestartedOnlineCheckInWindow(registration) {
  const createdAt = registration?.createdAt
  const startedAt = registration?.onlineCheckInStartedAt
  return typeof createdAt === 'number' && Number.isFinite(createdAt) &&
    typeof startedAt === 'number' && Number.isFinite(startedAt) &&
    startedAt !== createdAt
}

async function openOnlineJoin() {
  if (!onlineRegistrationAvailable.value) return
  if (!playerAccountSessionReady.value) await refreshLoggedInPlayerSession()
  const desiredAudience = playerAccount.value?.profile?.qq_number ? 'SELF' : 'OTHER'
  const canResumeCurrentResult = onlineJoinAudience.value === desiredAudience &&
    ['PENDING', 'REJECTED', 'UNKNOWN'].includes(onlineJoinStep.value)
  if (canResumeCurrentResult) {
    onlineJoinVisible.value = true
    return
  }
  resetOnlineJoin(desiredAudience)
  onlineJoinVisible.value = true
  if (desiredAudience === 'SELF') {
    onlineJoinQq.value = playerAccount.value.profile.qq_number
    onlineJoinStep.value = 'SELF_LOADING'
    queryOnlineProfile()
  }
}

function closeOnlineJoin() {
  onlineJoinClosingWarning.value = null
  onlineJoinVisible.value = false
}

function beginOtherOnlineJoin() {
  resetOnlineJoin('OTHER')
  onlineJoinVisible.value = true
}

function handleOnlineJoinQqInput(event) {
  onlineJoinQq.value = String(event.target.value || '').replace(/\D/g, '').slice(0, 12)
  onlineJoinError.value = ''
}

// Choosing a machine at which a new registration may not play before closing time asks first, as
// on the terminal.
function selectOnlineJoinMachine(machine) {
  if (!machine.available || onlineJoinLoading.value) return
  if (machine.id !== onlineJoinMachineId.value && onlineJoinNeedsClosingWarning(machine)) {
    onlineJoinClosingWarning.value = { machineId: machine.id, continuation: 'SELECT' }
    return
  }
  applyOnlineJoinMachine(machine)
}

// 仍然继续: the machine is not asked about again during this join, and what the warning held
// back goes on.
function confirmOnlineJoinClosingWarning() {
  const warning = onlineJoinClosingWarning.value
  if (!warning) return
  onlineJoinClosingWarning.value = null
  onlineJoinClosingAcknowledgedMachineIds.value = [
    ...onlineJoinClosingAcknowledgedMachineIds.value,
    warning.machineId
  ]
  if (warning.continuation === 'SUBMIT') {
    submitOnlineJoin()
    return
  }
  const machine = onlineJoinMachineOptions.value.find((candidate) => candidate.id === warning.machineId)
  if (machine?.available) applyOnlineJoinMachine(machine)
}

function applyOnlineJoinMachine(machine) {
  onlineJoinMachineId.value = machine.id
  onlineJoinPreference.value = machine.capacity === 1
    ? 'SOLO'
    : onlineJoinProfile.value?.defaultPreference === 'ASK_EVERY_TIME'
      ? null
      : onlineJoinProfile.value?.defaultPreference || null
  onlineJoinCommandId.value = null
  onlineJoinError.value = ''
}

function selectOnlineJoinPreference(preference) {
  onlineJoinPreference.value = preference
  if (preference !== 'SOLO') onlineJoinPassingAnswer.value = null
  onlineJoinCommandId.value = null
  onlineJoinError.value = ''
}

async function queryOnlineProfile() {
  if (!onlineRegistrationAvailable.value) {
    onlineJoinError.value = `${onlineRegistrationSummary.value}，请等待页面刷新后重试。`
    return
  }
  const qqNumber = normalizeQqNumber(onlineJoinQq.value)
  if (!qqNumber) {
    onlineJoinError.value = '请输入 5 至 12 位数字的 QQ 号。'
    return
  }
  if (!selectedOnlineJoinMachine.value?.available) {
    onlineJoinError.value = '请选择当前可以接收新登记的机台。'
    return
  }
  onlineJoinLoading.value = true
  onlineJoinError.value = ''
  try {
    const response = await fetch(QUEUE_ONLINE_PROFILE_API_URL, {
      method: 'POST',
      cache: 'no-store',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ qq: qqNumber })
    })
    const data = await readJsonResponse(response)
    const profile = normalizeOnlineProfile(data.profile)
    if (!profile) throw new Error('服务器返回的玩家资料不完整，请在现场终端检查资料。')
    onlineJoinProfile.value = profile
    onlineJoinQueueId.value = data.queue_id ?? data.queueId ?? queueId.value
    onlineJoinMachineConfigurationRevision.value = Math.max(
      1,
      toNonNegativeInteger(
        data.machine_configuration_revision ?? data.machineConfigurationRevision
      ) || machineConfigurationRevision.value
    )
    const remoteMachines = Array.isArray(data.machines)
      ? data.machines.map(normalizeOnlineMachine)
        .filter((machine) => SUPPORTED_MACHINE_IDS.includes(machine.id))
        .sort((first, second) => (
          SUPPORTED_MACHINE_IDS.indexOf(first.id) - SUPPORTED_MACHINE_IDS.indexOf(second.id)
        ))
      : []
    onlineJoinMachines.value = remoteMachines
    onlineJoinGroups.value = normalizeOnlineGroups(data, remoteMachines)
    if (!onlineJoinMachines.value.some((machine) => machine.id === onlineJoinMachineId.value)) {
      onlineJoinMachineId.value = firstAvailableOnlineMachineId()
    }
    onlineJoinExistingRegistration.value = data.existing_registration ??
      data.existingRegistration ?? null
    onlineJoinPreference.value = selectedOnlineJoinMachine.value?.capacity === 1
      ? 'SOLO'
      : profile.defaultPreference === 'ASK_EVERY_TIME'
        ? null
        : profile.defaultPreference
    onlineJoinStep.value = onlineJoinExistingRegistration.value ? 'EXISTING' : 'CONFIRM'
  } catch (error) {
    if (onlineJoinAudience.value === 'SELF' && onlineJoinStep.value === 'SELF_LOADING') {
      onlineJoinStep.value = 'SELF_ERROR'
    }
    onlineJoinError.value = error?.message || '暂时无法查询玩家资料，请稍后重试。'
  } finally {
    onlineJoinLoading.value = false
  }
}

function retryOwnOnlineJoin() {
  if (!playerAccount.value?.profile?.qq_number) {
    beginOtherOnlineJoin()
    return
  }
  onlineJoinAudience.value = 'SELF'
  onlineJoinQq.value = playerAccount.value.profile.qq_number
  onlineJoinError.value = ''
  onlineJoinStep.value = 'SELF_LOADING'
  queryOnlineProfile()
}

function backToOnlineLookup() {
  onlineJoinAudience.value = 'OTHER'
  onlineJoinStep.value = 'LOOKUP'
  onlineJoinProfile.value = null
  onlineJoinMachines.value = []
  onlineJoinGroups.value = []
  onlineJoinExistingRegistration.value = null
  onlineJoinPreference.value = null
  onlineJoinQueueId.value = null
  onlineJoinMachineConfigurationRevision.value = null
  onlineJoinCommandId.value = null
  onlineJoinResultRegistrationId.value = null
  onlineJoinTerminalApplied.value = false
  onlineJoinError.value = ''
}

function retryRejectedOnlineJoin() {
  if (onlineJoinAudience.value === 'SELF') {
    retryOwnOnlineJoin()
  } else {
    backToOnlineLookup()
  }
}

function playerGenderText(gender) {
  if (gender === 'MALE') return '♂'
  if (gender === 'FEMALE') return '♀'
  return '—'
}

function playerGenderLabel(gender) {
  if (gender === 'MALE') return '男'
  if (gender === 'FEMALE') return '女'
  return '不愿透露'
}

function profilePreferenceText(preference) {
  if (preference === 'SOLO') return '单人游玩'
  if (preference === 'OPEN_TO_JOIN') return '允许他人加入'
  return '每次询问'
}

function existingOnlineRegistrationText() {
  const existing = onlineJoinExistingRegistration.value
  if (!existing) return ''
  const machineId = existing.machine_id ?? existing.machineId
  const position = existing.position
  const positionIndex = existing.position_index ?? existing.positionIndex
  // Named as the page names it, where the page shows the registration at the same machine and
  // in the same kind of position: previews of the other machine of a pair take a number as well,
  // so the page's number can be higher than the server's, which counts the machine's own
  // positions (as in the account dialog; website review W4).
  const registrationId = existing.registration_id ?? existing.registrationId
  const location = registrationId
    ? registrationLocations.value.find(({ registration }) => (
      registration.registrationId === registrationId
    ))
    : null
  if (
    location &&
    location.machine.id === machineId &&
    (location.kind === 'PLAYING') === (position === 'PLAYING')
  ) return location.label
  if (position === 'PLAYING') return `游玩位置 ${machineId}`
  return `位置 ${machineId}${positionIndex || ''}`
}

function markOnlinePlayerAsSelf(registration = null, allowReplacementPrompt = true) {
  if (playerAccount.value) return true
  const profile = onlineJoinProfile.value
  const targetQueueId = onlineJoinQueueId.value || queueId.value
  if (!profile || !targetQueueId) return false
  const registrationId = registration?.registration_id ?? registration?.registrationId ?? ''
  const machineId = registration?.machine_id ?? registration?.machineId ?? onlineJoinMachineId.value
  const playerIdentity = {
    queueId: targetQueueId,
    registrationId,
    registrationIds: [registrationId].filter(Boolean),
    displayId: profile.nickname,
    qqNumber: profile.qqNumber,
    markedAt: Date.now(),
    machineId
  }
  const continuesExistingMark = !markedSelf.value || isSameMarkedPlayer(
    markedSelf.value,
    playerIdentity
  )
  if (!continuesExistingMark) {
    if (allowReplacementPrompt) pendingSelfRegistration.value = playerIdentity
    return false
  }
  persistMarkedSelf({
    ...playerIdentity,
    registrationIds: [registrationId, ...knownSelfRegistrationIds()].filter(Boolean)
  })
  showMachineGroup(machines.value.find((machine) => machine.id === machineId))
  return true
}

function openExistingOnlineRegistration(registration = null) {
  const registrationId = registration?.registration_id ?? registration?.registrationId ?? ''
  const location = registrationId
    ? registrationLocations.value.find(({ registration: candidate }) => (
      candidate.registrationId === registrationId
    ))
    : null

  // Anonymous self lookups retain the existing local marker behavior. A lookup
  // made for someone else must never turn that person's registration into ours.
  if (onlineJoinAudience.value === 'SELF' && !playerAccount.value &&
    !markOnlinePlayerAsSelf(registration)) {
    closeOnlineJoin()
    return
  }

  closeOnlineJoin()
  if (!location) {
    loadQueue(true)
    return
  }
  showMachineGroup(location.machine)
  openRegistration(
    location.machine,
    location.registration,
    location.label,
    location.kind === 'PLAYING' ? null : location.estimate,
    location.kind !== 'PLAYING' && location.estimateUncertain,
    location.registrations,
    location.commonPlayPreview,
    location.kind === 'PLAYING'
  )
}

function openActiveSelfDetail() {
  const location = markedSelfLocation.value
  if (!location) return
  showMachineGroup(location.machine)
  openRegistration(
    location.machine,
    location.registration,
    location.label,
    location.kind === 'PLAYING' ? null : location.estimate,
    location.kind !== 'PLAYING' && location.estimateUncertain,
    location.registrations,
    location.commonPlayPreview,
    location.kind === 'PLAYING'
  )
}

function scheduleOnlineCommandPoll() {
  if (!onlineJoinCommandId.value) return
  if (onlineCommandTimer) window.clearTimeout(onlineCommandTimer)
  onlineCommandTimer = window.setTimeout(pollOnlineJoinCommand, ONLINE_COMMAND_POLL_INTERVAL)
}

function findAppliedOnlineRegistration(command) {
  const resultRegistrationId = command?.result_registration_id ?? command?.resultRegistrationId
  if (resultRegistrationId) {
    return registrationLocations.value.find(({ registration }) => (
      registration.registrationId === resultRegistrationId
    )) || null
  }

  const profileNickname = normalizePlayerNickname(onlineJoinProfile.value?.nickname)
  if (!profileNickname) return null
  const targetMachineId = command?.payload?.machine_id ?? command?.payload?.machineId ??
    onlineJoinMachineId.value
  const matches = registrationLocations.value.filter(({ registration, machine }) => (
    machine.id === targetMachineId &&
    registration.onlineRegistrationPendingCheckIn &&
    normalizePlayerNickname(registration.displayId) === profileNickname
  ))
  return matches.length === 1 ? matches[0] : null
}

async function pollOnlineJoinCommand() {
  const commandId = onlineJoinCommandId.value
  if (!commandId) return
  try {
    const response = await fetchWithTimeout(`${QUEUE_ONLINE_COMMAND_API_BASE}/${encodeURIComponent(commandId)}`, {
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    })
    const command = await readJsonResponse(response)
    if (commandId !== onlineJoinCommandId.value) return
    onlineJoinPollFailures = 0
    onlineJoinConfirming.value = false
    if (command.status === 'PENDING') {
      scheduleOnlineCommandPoll()
      return
    }
    if (command.status === 'APPLIED') {
      onlineJoinTerminalApplied.value = true
      const appliedLocation = findAppliedOnlineRegistration(command)
      const resultRegistrationId = command?.result_registration_id ?? command?.resultRegistrationId
      onlineJoinResultRegistrationId.value = resultRegistrationId || null
      onlineJoinResultDetail.value = command.result_detail || '线上登记已经加入等待顺序。'
      onlineJoinStep.value = 'SUCCESS'
      showMachineGroup(machines.value.find((machine) => machine.id === onlineJoinMachineId.value))
      markOnlinePlayerAsSelf(
        appliedLocation?.registration || { registrationId: resultRegistrationId || '' },
        false
      )
      onlineJoinCommandId.value = null
      loadQueue(true)
      return
    }
    onlineJoinTerminalApplied.value = false
    onlineJoinResultRegistrationId.value = null
    onlineJoinResultDetail.value = command.result_detail || '现场终端没有执行这次线上登记。'
    onlineJoinCommandId.value = null
    onlineJoinStep.value = 'REJECTED'
  } catch (error) {
    if (commandId !== onlineJoinCommandId.value) return
    if (!isConnectionLost(error)) {
      scheduleOnlineCommandPoll()
      return
    }
    // The reading got no answer: the dialog says the result is being confirmed, and the reading
    // is made again, a little later each time, at once when the browser is online again or the
    // page is shown again (website review W-L7).
    onlineJoinPollFailures += 1
    onlineJoinConfirming.value = true
    if (onlineCommandTimer) window.clearTimeout(onlineCommandTimer)
    onlineCommandTimer = null
    await waitForConnection(retryDelay(onlineJoinPollFailures))
    if (commandId === onlineJoinCommandId.value && !onlineCommandTimer) pollOnlineJoinCommand()
  }
}

async function submitOnlineJoin() {
  if (!onlineRegistrationAvailable.value) {
    onlineJoinError.value = `${onlineRegistrationSummary.value}，请等待页面刷新后重试。`
    return
  }
  const profile = onlineJoinProfile.value
  const machine = selectedOnlineJoinMachine.value
  if (!profile || !machine?.available) {
    onlineJoinError.value = '所选机台当前不能接收新的登记，请重新查询。'
    return
  }
  if (
    !onlineJoinQueueId.value ||
    !onlineJoinMachineConfigurationRevision.value
  ) {
    invalidateOnlineJoinConfirmation('登记确认信息已经失效，请重新查询玩家资料后再提交。')
    return
  }
  if (
    queueId.value !== onlineJoinQueueId.value ||
    machineConfigurationRevision.value !== onlineJoinMachineConfigurationRevision.value
  ) {
    invalidateOnlineJoinConfirmation('现场队列或机台配置已更新，请重新查询玩家资料后再提交。')
    return
  }
  const currentMachine = machines.value.find((candidate) => candidate.id === machine.id)
  if (!currentMachine || currentMachine.stableId !== machine.stableId) {
    invalidateOnlineJoinConfirmation('所选机台已经变化，请重新查询玩家资料后再提交。')
    return
  }
  const preference = machine.capacity === 1
    ? 'SOLO'
    : onlineJoinNeedsPreference.value
    ? onlineJoinPreference.value
    : machine.capacity === 1
      ? 'SOLO'
      : profile.defaultPreference
  if (!['SOLO', 'OPEN_TO_JOIN'].includes(preference)) {
    onlineJoinError.value = '请选择本次游玩偏好。'
    return
  }
  // The machine chosen for the player when the dialog opened was never asked about.
  if (onlineJoinNeedsClosingWarning(machine)) {
    onlineJoinClosingWarning.value = { machineId: machine.id, continuation: 'SUBMIT' }
    return
  }
  const requestId = onlineJoinCommandId.value || createRequestId()
  onlineJoinCommandId.value = requestId
  const requestPayload = {
    request_id: requestId,
    qq: profile.qqNumber,
    machine_id: machine.id,
    preference,
    expected_queue_id: onlineJoinQueueId.value,
    expected_machine_configuration_revision: onlineJoinMachineConfigurationRevision.value,
    expected_machine_stable_id: machine.stableId
  }
  // The tick box belongs to this registration alone. It is sent only where it was offered.
  if (onlineJoinPairedMachine.value) {
    requestPayload.also_queues_at_paired_machine = onlineJoinPairedTick.value
  }
  // Sent, ticked or not, wherever the box was offered: the terminal then remembers the answer
  // for the profile. The box is offered only where the terminal knows the choice.
  if (preference === 'SOLO' && onlineJoinPassingAvailable.value) {
    requestPayload.allows_passing = onlineJoinPassing.value
  }
  await deliverOnlineJoin(requestId, requestPayload)
}

// Sends the online registration and follows it. A request that gets no answer is sent again
// unchanged, with the same request_id, so that the server returns the command it already holds
// instead of creating a second registration; meanwhile the dialog says the result is being
// confirmed, and when the server stays out of reach it says the result is unknown and offers
// 重新提交, which sends the same request once more (website review W-L7).
async function deliverOnlineJoin(requestId, requestPayload, resending = false) {
  onlineJoinTerminalApplied.value = false
  onlineJoinConfirming.value = resending
  onlineJoinLoading.value = true
  onlineJoinError.value = ''
  const following = () => onlineJoinCommandId.value === requestId
  const sendRequest = (payload) => {
    onlineJoinPendingRequest = { requestId, payload }
    return retryWhileConnectionLost(async () => readJsonResponse(await fetchWithTimeout(QUEUE_ONLINE_JOIN_API_URL, {
      method: 'POST',
      cache: 'no-store',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })), {
      isActive: following,
      onConnectionLost: () => {
        onlineJoinConfirming.value = true
        onlineJoinStep.value = 'PENDING'
      }
    })
  }
  try {
    let command
    try {
      command = await sendRequest(requestPayload)
    } catch (error) {
      if (
        error?.status !== 400 ||
        error?.message !== '请求包含不支持的线上登记字段'
      ) throw error
      const { expected_machine_stable_id: _ignored, ...legacyPayload } = requestPayload
      command = await sendRequest(legacyPayload)
    }
    if (!following()) return
    onlineJoinConfirming.value = false
    onlineJoinPollFailures = 0
    onlineJoinCommandId.value = command.command_id || requestId
    onlineJoinStep.value = 'PENDING'
    if (command.status === 'APPLIED' || command.status === 'REJECTED') {
      await pollOnlineJoinCommand()
    } else {
      scheduleOnlineCommandPoll()
    }
  } catch (error) {
    if (!following()) return
    onlineJoinConfirming.value = false
    if (isConnectionLost(error)) {
      // The registration may have been created.
      onlineJoinStep.value = 'UNKNOWN'
      return
    }
    // The server answered and refused the request: the confirmation is shown again.
    if (['PENDING', 'UNKNOWN'].includes(onlineJoinStep.value)) onlineJoinStep.value = 'CONFIRM'
    if (error?.code === 'QUEUE_CONTEXT_CHANGED') {
      invalidateOnlineJoinConfirmation(
        error?.message || '现场队列或机台配置已更新，请重新查询玩家资料后再提交。'
      )
    } else {
      onlineJoinError.value = error?.message || '线上登记暂时无法提交，请稍后重试。'
    }
  } finally {
    onlineJoinLoading.value = false
  }
}

// 重新提交 when the result is unknown: the same request, with the same request_id.
function resendOnlineJoin() {
  const pending = onlineJoinPendingRequest
  if (!pending || pending.requestId !== onlineJoinCommandId.value || onlineJoinLoading.value) return
  onlineJoinStep.value = 'PENDING'
  deliverOnlineJoin(pending.requestId, pending.payload, true)
}

function handleKeydown(event) {
  if (event.key !== 'Escape') return
  if (playerAccountDialogVisible.value) return
  if (onlineJoinClosingWarning.value) onlineJoinClosingWarning.value = null
  else if (onlineJoinVisible.value) closeOnlineJoin()
  else if (versionDialogVisible.value) closeVersionDialog()
  else if (pendingSelfRegistration.value) pendingSelfRegistration.value = null
  else closeDetail()
}

onMounted(async () => {
  const query = new URLSearchParams(window.location.search)
  mobileRegistrationToken.value = query.get('mobile_registration') || ''
  mobileProfileCreationToken.value = query.get('player_profile_creation') || ''
  playerAccountBindingToken.value = query.get('account_binding') || ''
  playerAccountDialogVisible.value = Boolean(playerAccountBindingToken.value)
  if (playerAccountBindingToken.value) {
    const url = new URL(window.location.href)
    url.searchParams.delete('account_binding')
    window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`)
  }
  if (mobileRegistrationToken.value || mobileProfileCreationToken.value) {
    await nextTick()
    return
  }
  restoreMarkedSelf()
  await loadQueue()
  await refreshLoggedInPlayerSession()
  refreshTimer = window.setInterval(() => loadQueue(true), REFRESH_INTERVAL)
  clockTimer = window.setInterval(() => { currentTime.value = Date.now() }, 30000)
  window.addEventListener('keydown', handleKeydown)
  await nextTick()
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (clockTimer) window.clearInterval(clockTimer)
  if (onlineCommandTimer) window.clearTimeout(onlineCommandTimer)
  clearOperationFeedback()
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <MobileRegistrationFlow v-if="mobileRegistrationToken" :token="mobileRegistrationToken" />
  <MobileProfileCreationFlow v-else-if="mobileProfileCreationToken" :token="mobileProfileCreationToken" />
  <main v-else class="queue-panel">
    <header class="queue-header">
      <div class="queue-heading">
        <span v-if="venueHeading" class="queue-venue">
          {{ venueHeading.name }}
        </span>
        <h1>排队登记</h1>
        <p>
          <span>{{ machineCountSummary }}</span>
          <template v-if="hasSnapshot">
            <span class="queue-heading-separator" aria-hidden="true"></span>
            <strong>当前共 {{ totalRegistrationCount }} 份登记</strong>
          </template>
        </p>
      </div>

      <div class="queue-toolbar">
        <div class="queue-view-tabs" aria-label="页面内容">
          <button :class="{ active: activeView === 'queue' }" type="button" @click="switchView('queue')">
            排队状态
          </button>
          <button :class="{ active: activeView === 'logs' }" type="button" @click="switchView('logs')">
            <History :size="15" aria-hidden="true" />
            日志
          </button>
        </div>
        <div class="queue-system">
          <time :datetime="capturedAt || undefined">{{ capturedTimeText }}</time>
          <div class="queue-system-status">
            <template v-if="availability">
              <span class="queue-system-divider" aria-hidden="true"></span>
              <span class="queue-availability" :class="`is-${availability.tone}`">
                <span aria-hidden="true"></span>
                {{ availability.label }}
              </span>
            </template>
          </div>
          <div class="queue-system-actions">
            <button
              class="queue-version-button"
              type="button"
              aria-label="打开玩家资料"
              title="玩家资料"
              @click="openPlayerAccount"
            >
              <UserRound :size="18" aria-hidden="true" />
            </button>
            <button
              class="queue-version-button"
              type="button"
              aria-label="查看机厅与系统信息"
              title="机厅与系统"
              @click="openVersionDialog"
            >
              <Info :size="18" aria-hidden="true" />
            </button>
            <button
              class="queue-refresh"
              type="button"
              :disabled="refreshing"
              aria-label="刷新排队状态"
              title="刷新排队状态"
              @click="activeView === 'logs' ? loadCurrentLogs(true) : loadQueue()"
            >
              <RefreshCw :size="18" :class="{ spinning: refreshing || logsLoading }" aria-hidden="true" />
            </button>
          </div>
        </div>
      </div>
    </header>

    <section v-if="hasSnapshot && testData" class="queue-test-notice" aria-live="polite">
      <TriangleAlert :size="17" aria-hidden="true" />
      <strong>当前数据是测试数据。</strong>
    </section>

    <section v-if="hasSnapshot && snapshotStale" class="queue-stale-notice" aria-live="polite">
      <TriangleAlert :size="17" aria-hidden="true" />
      <div>
        <strong>队列数据已过期</strong>
        <span>{{ capturedTimeText }}。以下内容仅供辨认，时间估算已暂停显示。</span>
      </div>
    </section>

    <section v-if="activeSelfIdentity" class="queue-self" :class="[`is-${markedSelfTone()}`, { 'is-clickable': markedSelfLocation }]"
      :role="markedSelfLocation ? 'button' : undefined" :tabindex="markedSelfLocation ? 0 : undefined"
      aria-live="polite" @click="openActiveSelfDetail"
      @keydown.enter.self.prevent="openActiveSelfDetail" @keydown.space.self.prevent="openActiveSelfDetail">
      <div class="queue-self-icon" aria-hidden="true">
        <UserRoundCheck :size="22" />
      </div>
      <div class="queue-self-main">
        <span class="queue-self-eyebrow">我的排队</span>
        <h2>{{ activeSelfIdentity.displayId }}</h2>
        <strong class="queue-self-status-badge">{{ markedSelfStatusTitle() }}</strong>
        <p>{{ markedSelfStatusDetail() }}</p>
        <div v-if="markedSelfLocation" class="queue-self-facts">
          <span><MapPin :size="13" aria-hidden="true" />{{ markedSelfLocation.label }}</span>
          <span v-if="markedSelfLocation.registration.noShowCount > 0">
            <TriangleAlert :size="13" aria-hidden="true" />
            {{ noShowResultLabel(markedSelfLocation.registration) }}
          </span>
          <span v-if="markedSelfLocation.registrations.length > 1">
            <Users :size="13" aria-hidden="true" />共同游玩
          </span>
          <span v-if="!accountSessionActive && !selfStorageAvailable" class="is-warning">
            仅在本次浏览期间保留
          </span>
        </div>
      </div>
      <button v-if="!accountSessionActive" class="queue-self-clear" type="button" @click.stop="clearMarkedSelf">取消标记</button>
      <span v-else class="queue-self-account-badge">已登录</span>
    </section>

    <section v-if="operationFeedback.length" class="queue-feedback" aria-live="polite">
      <header>
        <strong>最近操作</strong>
        <button type="button" @click="clearOperationFeedback">清除</button>
      </header>
      <article v-for="feedback in operationFeedback" :key="feedback.id" :class="`is-${feedback.kind}`">
        <div>
          <strong>{{ feedback.title }}</strong>
          <p>{{ feedback.detail }}</p>
          <button v-if="feedback.retry" type="button" @click="retrySafeRefresh">刷新最新状态</button>
        </div>
        <button class="queue-feedback-dismiss" type="button" aria-label="关闭操作结果" title="关闭" @click="dismissOperationFeedback(feedback.id)">
          <X :size="15" aria-hidden="true" />
        </button>
      </article>
    </section>

    <section v-if="hasSnapshot && activeView === 'queue'" class="queue-online-entry"
      :class="{ 'is-disabled': !onlineRegistrationAvailable }">
      <span class="queue-online-entry-icon" aria-hidden="true"><UserPlus :size="20" /></span>
      <div>
        <strong>线上登记</strong>
        <span>{{ onlineRegistrationSummary }}</span>
      </div>
      <button type="button" :disabled="!onlineRegistrationAvailable" @click="openOnlineJoin">
        加入排队
      </button>
    </section>

    <section v-if="!hasSnapshot" class="queue-unavailable" aria-live="polite">
      <span class="queue-unavailable-icon" aria-hidden="true"><WifiOff :size="22" /></span>
      <div>
        <h2>{{ loading ? '正在读取现场队列' : '排队终端暂未接入' }}</h2>
        <p>{{ loading ? '正在等待终端返回最新登记状态。' : '现场终端与服务端同步后，这里会显示各机台的实时队列。' }}</p>
      </div>
    </section>

    <template v-else-if="activeView === 'queue'">
      <section
        v-if="outsideBusinessHours || registrationOpen === false"
        class="queue-natural-notice"
        :class="{ 'is-outside': outsideBusinessHours }"
      >
        <TriangleAlert v-if="outsideBusinessHours" :size="19" aria-hidden="true" />
        <CircleCheck v-else :size="19" aria-hidden="true" />
        <div>
          <strong>{{ outsideBusinessHours ? '不在营业时间' : '当前未使用登记排队' }}</strong>
          <span v-if="outsideBusinessHours">
            {{ businessHoursClosingGrace
              ? '今日营业时间已结束，现场正在完成现有队列；收尾期间不再接收新的排队登记。'
              : registrationOpen
                ? '现场已在营业时间外手动开启登记排队。'
                : '登记排队已经关闭；现场手动开启后，可以继续使用登记排队。' }}
          </span>
          <span v-else>请按照现场顺序排队，并留意其他玩家的安排。</span>
        </div>
      </section>

      <nav v-if="configuredMachineGroups.length > 1" class="queue-machine-groups"
        :class="{ 'is-pair': configuredMachineGroups.length === 2 }" aria-label="机台分组">
        <button v-for="group in configuredMachineGroups" :key="group.id" type="button"
          :class="{ active: activeMachineGroup?.id === group.id }"
          :aria-pressed="activeMachineGroup?.id === group.id" :title="group.name"
          @click="selectMachineGroup(group.id)">
          <span>{{ group.name }}</span>
          <small>{{ group.machines.map((machine) => machine.id).join('、') }}</small>
        </button>
      </nav>

      <div class="queue-machine-list" :class="{ 'is-single': visibleMachines.length === 1 }" :aria-busy="loading">
        <section v-for="machine in visibleMachines" :key="machine.stableId" class="queue-machine">
          <header class="queue-machine-header">
            <button type="button" :aria-label="`查看 ${machine.name} 详情`" @click="openMachineDetails(machine)">
              <span>
                <strong class="queue-machine-title">{{ machine.name }}</strong>
                <span class="queue-machine-summary">{{ machineSummary(machine) }}</span>
              </span>
              <ChevronRight :size="18" aria-hidden="true" />
            </button>
            <span v-if="!machine.operational" class="queue-machine-state">已停止</span>
          </header>

          <div v-if="!machine.synced" class="queue-machine-message">
            <p>尚未同步这台机台</p>
            <span>现场终端尚未上传这台机台的状态。</span>
          </div>

          <template v-else>
            <div v-if="!machine.operational" class="queue-machine-message is-stopped">
              <p>机台已停止使用</p>
              <span>
                停止原因：{{ stopReasonLabel(machine.stopReason, machine.stopReasonDetail) }}。{{ machine.registrationCount > 0
                  ? `现有 ${machine.registrationCount} 份登记、游玩位置和等待顺序均已保留。`
                  : '当前没有排队登记。' }}
              </span>
            </div>

            <div class="queue-position-list">
              <article
                class="queue-position is-playing"
                :class="{ 'is-actionable': machine.playing.length }"
                :role="machine.playing.length ? 'button' : undefined"
                :tabindex="machine.playing.length ? 0 : undefined"
                @click="openPosition(machine)"
                @keydown.enter.self.prevent="openPosition(machine)"
                @keydown.space.self.prevent="openPosition(machine)"
              >
                <header>
                  <span>{{ playingLabel(machine) }}</span>
                  <ChevronRight v-if="machine.playing.length" :size="18" aria-hidden="true" />
                </header>
                <div
                  v-if="!machine.playing.length && !businessHoursClosingGrace && !(businessHoursClosingSoon && machine.operational)"
                  class="queue-position-empty"
                >暂无登记</div>
                <div v-else class="queue-registration-grid">
                  <div v-if="businessHoursClosingGrace" class="queue-overtime is-closing">
                     <strong>今日营业时间已结束</strong>
                     <span>不再接收新登记。现有队列处理完毕后将关闭，最迟保留 20 分钟。</span>
                   </div>
                  <div v-else-if="businessHoursClosingSoon && machine.operational" class="queue-overtime is-closing-soon">
                    <strong>将在 30 分钟内闭店</strong>
                    <span>{{ playingOvertime(machine)
                      ? '本轮已超过 20 分钟，请留意后续队列安排。'
                      : '请留意后续队列安排。' }}</span>
                  </div>
                  <div v-else-if="playingOvertime(machine)" class="queue-overtime">
                    <strong>本轮已超过 20 分钟</strong>
                    <span>请确认机台是否仍在正常游玩。</span>
                  </div>
                  <button
                    v-for="(registration, index) in machine.playing"
                    :key="registration.registrationId || `playing-${machine.id}-${index}`"
                    class="queue-registration"
                    :class="[`is-${registrationTone(registration)}`, { 'is-self': isMarkedRegistration(registration) }]"
                    :style="{ '--tile-width': registrationTileWidth(registration.displayId, registrationLabel(registration)) }"
                    type="button"
                    @click.stop="openRegistration(machine, registration, `游玩位置 ${machine.id}`, null, false, machine.playing, null, true)"
                  >
                    <strong>{{ registration.displayId }}</strong>
                    <span>{{ registrationLabel(registration) }}</span>
                    <UserRoundCheck v-if="isMarkedRegistration(registration)" :size="14" aria-label="我的登记" />
                    <ChevronRight v-else :size="15" aria-hidden="true" />
                  </button>
                </div>
              </article>

              <!-- A position that holds only previews of the other machine opens nothing; its
                previews open their own explanation (website review W9). -->
              <article
                v-for="(position, index) in machine.waitingPositions"
                :key="position.positionId || `${machine.id}-${index}`"
                class="queue-position"
                :class="{ 'is-actionable': position.registrations.length }"
                :role="position.registrations.length ? 'button' : undefined"
                :tabindex="position.registrations.length ? 0 : undefined"
                @click="openPosition(machine, position)"
                @keydown.enter.self.prevent="openPosition(machine, position)"
                @keydown.space.self.prevent="openPosition(machine, position)"
              >
                <header>
                  <div class="queue-position-heading">
                    <span>{{ positionLabel(machine, position, index) }}</span>
                    <small>{{ positionEstimateText(position.estimatedWaitMinutes, position.registrations, machine, position.estimatedWaitUncertain) }}</small>
                  </div>
                  <ChevronRight v-if="position.registrations.length" :size="18" aria-hidden="true" />
                </header>
                <div class="queue-registration-grid">
                  <button
                    v-for="(registration, registrationIndex) in position.registrations"
                    :key="registration.registrationId || `${machine.id}-${index}-${registrationIndex}`"
                    class="queue-registration"
                    :class="[`is-${registrationTone(registration)}`, { 'is-self': isMarkedRegistration(registration), 'is-announced': registrationIsAnnounced(registration, machine), 'is-passing': registrationPassingText(registration, machine) }]"
                    :style="{ '--tile-width': registrationTileWidth(registration.displayId, registrationLabel(registration, machine)) }"
                    type="button"
                    @click.stop="openRegistration(machine, registration, `位置 ${machine.id}${index + 1}`, position.estimatedWaitMinutes, position.estimatedWaitUncertain, position.registrations, position.commonPlayPreview)"
                  >
                    <strong>{{ registration.displayId }}</strong>
                    <span>{{ registrationLabel(registration, machine) }}</span>
                    <UserRoundCheck v-if="isMarkedRegistration(registration)" :size="14" aria-label="我的登记" />
                    <ChevronRight v-else :size="15" aria-hidden="true" />
                  </button>
                  <button
                    v-for="preview in position.pairedPreviews || []"
                    :key="`paired-${preview.registrationId}`"
                    class="queue-registration is-preview is-paired-preview"
                    :style="{ '--tile-width': registrationTileWidth(preview.displayId, preferenceLabel(preview)) }"
                    type="button"
                    @click.stop="openPairedPreview(machine, preview)"
                  >
                    <strong>{{ preview.displayId }}</strong>
                    <span>{{ preferenceLabel(preview) }}</span>
                    <ChevronRight :size="15" aria-hidden="true" />
                  </button>
                  <div v-if="position.commonPlayPreview" class="queue-registration is-preview"
                    :style="{ '--tile-width': registrationTileWidth(position.commonPlayPreview.displayId, '共同游玩预览', TILE_WITHOUT_ARROW_HORIZONTAL_SPACE) }">
                    <strong>{{ position.commonPlayPreview.displayId }}</strong>
                    <span>共同游玩预览</span>
                  </div>
                </div>
              </article>
            </div>
          </template>
        </section>
      </div>
    </template>

    <section v-else class="queue-logs" aria-live="polite">
      <header class="queue-logs-header">
        <div>
          <h2>队列日志</h2>
          <p>仅显示与公开排队状态有关的变动，不包含联系方式或玩家资料。</p>
        </div>
      </header>
      <div class="queue-log-filter-groups">
        <div class="queue-log-filters" aria-label="按范围筛选日志">
          <button v-for="filter in logMachineFilters" :key="filter.value" type="button"
            :class="{ active: logFilter === filter.value }" @click="logFilter = filter.value">
            {{ filter.label }}
          </button>
        </div>
        <div class="queue-log-filters" aria-label="按操作来源筛选日志">
          <button v-for="source in logSourceDefinitions" :key="source.value" type="button"
            :class="{ active: logSourceFilter === source.value }"
            @click="logSourceFilter = source.value">
            {{ source.label }}
          </button>
        </div>
      </div>

      <div v-if="logsLoading" class="queue-logs-empty">
        <RefreshCw class="spinning" :size="20" aria-hidden="true" />
        <span>正在读取日志</span>
      </div>
      <div v-else-if="logsError" class="queue-logs-empty is-error">
        <TriangleAlert :size="20" aria-hidden="true" />
        <strong>暂时无法读取日志</strong>
        <span>队列状态仍可正常查看。请稍后重新读取日志。</span>
        <button type="button" @click="loadCurrentLogs(true)">重新读取</button>
      </div>
      <div v-else-if="filteredLogs.length === 0" class="queue-logs-empty">
        <History :size="20" aria-hidden="true" />
        <strong>{{ currentLogs.length ? '这个筛选范围内没有日志' : '还没有可显示的队列日志' }}</strong>
        <span>现场终端同步后发生的队列变动会显示在这里。</span>
      </div>
      <ol v-else class="queue-log-list">
        <li v-for="event in filteredLogs" :key="event.eventId"
          :class="{ 'is-self': eventIsMarkedSelf(event) }">
          <div class="queue-log-time">
            <time :datetime="event.occurredAt || undefined">{{ fullTimeText(event.occurredAt) }}</time>
            <span>{{ logMachineLabel(event) }}</span>
          </div>
          <div class="queue-log-body">
            <div class="queue-log-title">
              <strong>{{ event.title }}</strong>
              <span>{{ eventTypeLabel(event.type) }}</span>
              <span>{{ operationSourceLabel(event.operationSource) }}</span>
              <em v-if="eventIsMarkedSelf(event)">与我有关</em>
            </div>
            <p>{{ event.detail }}</p>
          </div>
        </li>
      </ol>
      <button v-if="currentLogsNextCursor && !logsError" class="queue-load-more" type="button"
        :disabled="logsLoadingMore" @click="loadCurrentLogs(false)">
        {{ logsLoadingMore ? '正在读取' : '查看更早的日志' }}
      </button>
    </section>

    <Teleport to="body">
      <PlayerAccountDialog
        v-if="playerAccountDialogVisible"
        :binding-token="playerAccountBindingToken"
        :focus-registration-id="playerAccountFocusRegistrationId"
        :capabilities="capabilities"
        :solo-pass-states="soloPassStates"
        :queue-page-places="queuePagePlaces"
        :waiting-position-counts="waitingPositionCounts"
        :queue-page-registrations="queuePageRegistrations"
        @close="closePlayerAccount"
        @bound="handlePlayerAccountBound"
        @session="handlePlayerAccountSession"
        @queue-state="handlePlayerAccountQueueState"
      />
      <Transition name="queue-dialog">
        <div v-if="versionDialogVisible" class="queue-detail-backdrop" @click.self="closeVersionDialog">
          <section class="queue-detail-dialog queue-version-dialog" role="dialog" aria-modal="true"
            aria-label="机厅与系统信息">
            <header class="queue-detail-header">
              <div>
                <h2>机厅与系统</h2>
                <p>当前机厅、现场终端和版本信息</p>
              </div>
              <button type="button" aria-label="关闭机厅与系统信息" title="关闭" @click="closeVersionDialog">
                <X :size="20" aria-hidden="true" />
              </button>
            </header>

            <section class="queue-system-details" aria-labelledby="queue-system-venue-title">
              <h3 id="queue-system-venue-title">当前机厅</h3>
              <dl>
                <div><dt>机厅名称</dt><dd>{{ venueHeading?.name || '尚未提供' }}</dd></div>
                <div><dt>机厅 ID</dt><dd>{{ venueHeading?.code || '尚未分配' }}</dd></div>
                <div><dt>现场终端</dt><dd>{{ terminal?.name || '尚未上报' }}</dd></div>
                <div><dt>终端状态</dt><dd>{{ terminalStatusLabel }}</dd></div>
              </dl>
            </section>

            <h3 class="queue-version-section-title">版本信息</h3>
            <div v-if="clientVersionsLoading && !clientVersions" class="queue-version-loading" aria-live="polite">
              <RefreshCw :size="20" class="spinning" aria-hidden="true" />
              <span>正在读取版本信息</span>
            </div>
            <template v-else>
              <p v-if="clientVersionsError" class="queue-version-error" role="status">
                {{ clientVersions ? '更新失败，以下为上次读取的结果。' : '暂时无法读取版本信息，请稍后重试。' }}
              </p>
              <div class="queue-version-list">
                <section v-for="row in clientVersionRows" :key="row.key">
                  <header>
                    <strong>{{ row.name }}</strong>
                    <span :class="`is-${row.status.toLowerCase().replace('_', '-')}`">
                      {{ row.statusLabel }}
                    </span>
                  </header>
                  <dl>
                    <div><dt>当前版本</dt><dd>{{ row.currentVersion || '未知' }}</dd></div>
                    <div><dt>最新版本</dt><dd>{{ row.latestVersion || '未知' }}</dd></div>
                    <div><dt>最后上报</dt><dd>{{ fullTimeText(row.updatedAt, '尚未上报') }}</dd></div>
                  </dl>
                </section>
              </div>
              <button v-if="clientVersionsError" class="queue-detail-secondary" type="button"
                :disabled="clientVersionsLoading" @click="loadClientVersions">
                <RefreshCw :size="17" :class="{ spinning: clientVersionsLoading }" aria-hidden="true" />
                重新获取
              </button>
            </template>
          </section>
        </div>
      </Transition>

      <Transition name="queue-dialog">
        <div v-if="onlineJoinVisible" class="queue-detail-backdrop" @click.self="closeOnlineJoin">
          <section class="queue-detail-dialog queue-online-dialog" role="dialog" aria-modal="true"
            aria-label="加入排队">
            <header class="queue-detail-header">
              <div>
                <h2>{{ onlineJoinStep === 'LOOKUP' ? '加入排队'
                  : onlineJoinStep === 'SELF_LOADING' ? '正在读取本人资料'
                    : onlineJoinStep === 'SELF_ERROR' ? '无法读取本人资料'
                  : onlineJoinStep === 'CONFIRM' ? '确认加入排队'
                    : onlineJoinStep === 'EXISTING' ? '已在排队'
                      : onlineJoinStep === 'PENDING' ? '正在提交登记'
                        : onlineJoinStep === 'REJECTED' ? '登记没有执行'
                          : onlineJoinStep === 'UNKNOWN' ? '暂时无法确认提交结果'
                            : '线上登记已完成' }}</h2>
                <p v-if="onlineJoinStep === 'LOOKUP'">使用已在现场终端创建的玩家资料</p>
                <p v-else-if="onlineJoinStep === 'SELF_LOADING'">正在使用当前登录的玩家资料</p>
                <p v-else-if="onlineJoinStep === 'SELF_ERROR'">当前登录资料暂时无法用于线上登记</p>
                <p v-else-if="onlineJoinStep === 'CONFIRM'">{{ onlineJoinConfirmSubtitle }}</p>
                <p v-else-if="onlineJoinStep === 'PENDING' && !onlineJoinConfirming">正在等待现场终端处理</p>
              </div>
              <button type="button" aria-label="关闭加入排队" title="关闭" @click="closeOnlineJoin">
                <X :size="20" aria-hidden="true" />
              </button>
            </header>

            <section v-if="onlineJoinStep === 'SELF_LOADING'" class="queue-online-result" aria-live="polite">
              <span class="queue-online-result-icon">
                <RefreshCw :size="23" class="spinning" />
              </span>
              <strong>正在确认玩家资料</strong>
              <p>将使用当前登录的玩家资料，不会显示完整玩家资料库。</p>
            </section>

            <section v-else-if="onlineJoinStep === 'SELF_ERROR'" class="queue-online-result is-rejected" aria-live="assertive">
              <span class="queue-online-result-icon is-rejected">
                <TriangleAlert :size="23" />
              </span>
              <strong>暂时无法确认本人资料</strong>
              <p>{{ onlineJoinError }}</p>
              <button class="queue-online-primary" type="button" :disabled="onlineJoinLoading" @click="retryOwnOnlineJoin">
                重新读取本人资料
              </button>
              <button class="queue-online-secondary" type="button" @click="beginOtherOnlineJoin">
                为他人创建线上登记
              </button>
            </section>

            <form v-else-if="onlineJoinStep === 'LOOKUP'" class="queue-online-form" @submit.prevent="queryOnlineProfile">
              <div class="queue-online-other-notice">
                <TriangleAlert :size="18" aria-hidden="true" />
                <p>
                  <strong>为他人创建线上登记</strong>
                  <span>请先取得本人同意。登记创建后，必须由本人到现场终端点击“已到场”完成签到；创建者不能代替签到，也不能代替他人操作登记。</span>
                </p>
              </div>
              <label class="queue-online-field">
                <span>QQ 号</span>
                <input :value="onlineJoinQq" inputmode="numeric" autocomplete="off" maxlength="12"
                  placeholder="输入玩家资料绑定的 QQ 号" @input="handleOnlineJoinQqInput" />
              </label>
              <fieldset class="queue-online-options">
                <legend>选择机台</legend>
                <div class="queue-online-machine-groups">
                  <section v-for="group in onlineJoinMachineGroups" :key="group.id">
                    <h3 v-if="onlineJoinMachineGroups.length > 1">{{ group.name }}</h3>
                    <div class="queue-online-machine-options is-rows">
                      <button v-for="machine in group.machines" :key="machine.stableId || machine.id" type="button"
                        :class="{ active: onlineJoinMachineId === machine.id }" :disabled="!machine.available"
                        @click="selectOnlineJoinMachine(machine)">
                        <span class="queue-online-machine-main">
                          <strong>{{ machine.name }}</strong>
                          <span>{{ machine.row.description }}</span>
                        </span>
                        <span v-if="machine.row.badge" class="queue-online-machine-badge">{{ machine.row.badge }}</span>
                        <span v-else-if="machine.row.wait" class="queue-online-machine-wait">
                          <small>{{ NEW_REGISTRATION_WAIT_LABEL }}</small>
                          <strong>{{ machine.row.wait }}</strong>
                        </span>
                      </button>
                    </div>
                  </section>
                </div>
              </fieldset>
              <p v-if="onlineJoinError" class="queue-online-error" role="alert">{{ onlineJoinError }}</p>
              <button class="queue-online-primary" type="submit" :disabled="onlineJoinLoading">
                {{ onlineJoinLoading ? '正在查询' : '查询玩家资料' }}
              </button>
              <p class="queue-online-hint">没有找到资料时，请先在现场终端的玩家资料库中创建并绑定 QQ。</p>
            </form>

            <div v-else-if="onlineJoinStep === 'CONFIRM'" class="queue-online-form">
              <dl class="queue-online-profile">
                <div>
                  <dt>玩家昵称</dt>
                  <dd>{{ onlineJoinProfile.nickname }}</dd>
                </div>
                <div v-if="onlineJoinProfile.publicPlayerId">
                  <dt>玩家编号</dt>
                  <dd>{{ onlineJoinProfile.publicPlayerId }}</dd>
                </div>
                <div>
                  <dt>性别</dt>
                  <dd class="queue-online-gender" :class="`is-${onlineJoinProfile.gender.toLowerCase()}`">
                    <span aria-hidden="true">{{ playerGenderText(onlineJoinProfile.gender) }}</span>
                    {{ playerGenderLabel(onlineJoinProfile.gender) }}
                  </dd>
                </div>
                <div>
                  <dt>QQ</dt>
                  <dd>{{ onlineJoinProfile.qqNumber }}</dd>
                </div>
                <div>
                  <dt>默认游玩偏好</dt>
                  <dd>{{ profilePreferenceText(onlineJoinProfile.defaultPreference) }}</dd>
                </div>
              </dl>

              <fieldset class="queue-online-options">
                <legend>排队机台</legend>
                <div class="queue-online-machine-groups">
                  <section v-for="group in onlineJoinMachineGroups" :key="group.id">
                    <h3 v-if="onlineJoinMachineGroups.length > 1">{{ group.name }}</h3>
                    <div class="queue-online-machine-options is-rows">
                      <button v-for="machine in group.machines" :key="machine.stableId || machine.id" type="button"
                        :class="{ active: onlineJoinMachineId === machine.id }" :disabled="!machine.available"
                        @click="selectOnlineJoinMachine(machine)">
                        <span class="queue-online-machine-main">
                          <strong>{{ machine.name }}</strong>
                          <span>{{ machine.row.description }}</span>
                        </span>
                        <span v-if="machine.row.badge" class="queue-online-machine-badge">{{ machine.row.badge }}</span>
                        <span v-else-if="machine.row.wait" class="queue-online-machine-wait">
                          <small>{{ NEW_REGISTRATION_WAIT_LABEL }}</small>
                          <strong>{{ machine.row.wait }}</strong>
                        </span>
                      </button>
                    </div>
                  </section>
                </div>
              </fieldset>

              <fieldset v-if="onlineJoinNeedsPreference || onlineJoinPairedMachine || (onlineJoinSoloByDefault && onlineJoinPassingAvailable)" class="queue-online-options">
                <legend>本次游玩偏好</legend>
                <div v-if="onlineJoinNeedsPreference" class="queue-online-preference-options"
                  :class="{ 'is-stacked': onlineJoinPassingAvailable }">
                  <!-- In the terminal's order and words: 单人游玩 first, then 允许他人加入, which is
                    marked 推荐. The tick box sits inside 单人游玩, as on the terminal. -->
                  <div class="queue-online-preference-option" :class="{ active: onlineJoinPreference === 'SOLO' }">
                    <button type="button" @click="selectOnlineJoinPreference('SOLO')">
                      <strong>单人游玩</strong>
                      <span>{{ PREFERENCE_DESCRIPTIONS.SOLO }}</span>
                    </button>
                    <SoloPassTickBox v-if="onlineJoinPassingAvailable && onlineJoinPreference === 'SOLO'"
                      v-model="onlineJoinPassing" :disabled="onlineJoinLoading"
                      :explanation-shown="onlineJoinProfile?.passingExplanationShown === true"
                      :explanation-key="onlineJoinProfile?.profileId || ''" />
                  </div>
                  <button type="button" :class="{ active: onlineJoinPreference === 'OPEN_TO_JOIN' }"
                    @click="selectOnlineJoinPreference('OPEN_TO_JOIN')">
                    <strong>允许他人加入<em class="queue-online-recommended">{{ PREFERENCE_RECOMMENDED_BADGE }}</em></strong>
                    <span>{{ PREFERENCE_DESCRIPTIONS.OPEN_TO_JOIN }}</span>
                  </button>
                </div>
                <div v-if="onlineJoinSoloByDefault && onlineJoinPassingAvailable" class="queue-online-solo-default">
                  <p>本次将使用“单人游玩”。</p>
                  <SoloPassTickBox v-model="onlineJoinPassing" :disabled="onlineJoinLoading"
                    :explanation-shown="onlineJoinProfile?.passingExplanationShown === true"
                    :explanation-key="onlineJoinProfile?.profileId || ''" />
                </div>
                <PairedQueueTickBox v-if="onlineJoinPairedMachine" v-model="onlineJoinPairedTick"
                  :disabled="onlineJoinLoading"
                  :paired-machine-name="onlineJoinPairedMachine.name"
                  :explanation-shown="onlineJoinProfile?.pairedQueueExplanationShown === true"
                  :explanation-key="onlineJoinProfile?.profileId || ''" />
              </fieldset>

              <div v-if="onlineJoinSinglePlayerMachine" class="queue-online-capacity-notice">
                <Users :size="18" aria-hidden="true" />
                <p>
                  <strong>本次将使用“单人游玩”</strong>
                  <span>{{ selectedOnlineJoinMachine.name }} 仅能容纳一人游玩。玩家资料中的默认游玩偏好不会改变。</span>
                </p>
              </div>

              <!-- Under the choices that change it: the machine and the tick box for two machines. -->
              <div v-if="onlineJoinWaitSummary" class="queue-online-wait-summary" aria-live="polite">
                <span>{{ NEW_REGISTRATION_WAIT_LABEL }}</span>
                <strong>{{ onlineJoinWaitSummary }}</strong>
                <small>{{ NEW_REGISTRATION_WAIT_NOTE }}</small>
              </div>

              <div class="queue-online-check-in-notice">
                <TriangleAlert :size="18" aria-hidden="true" />
                <p>
                  <strong>须在 30 分钟内完成签到</strong>
                  <span>创建后，这份登记会显示为“线上登记·待签到”。请到现场终端点击本人的登记并选择“已到场”。超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动移除。完成签到后，这份登记在游玩一轮之前不能延后；第一次轮到时未到场，只能移除登记，不能暂缓一次或移至队尾。</span>
                  <span v-if="!onlineJoinProfile.setupComplete">这份玩家资料尚未补全排队通知设置和 QQ 显示范围。线上登记可以先创建；到场后须先在终端补全资料，才能签到。</span>
                </p>
              </div>
              <div v-if="onlineJoinAudience === 'OTHER'" class="queue-online-other-notice">
                <TriangleAlert :size="18" aria-hidden="true" />
                <p>
                  <strong>请确认已取得本人同意</strong>
                  <span>线上登记只代表排队意向，不代表本人已到场。请把机台和签到规则告知对方，并由对方本人到现场完成签到。</span>
                </p>
              </div>
              <p v-if="onlineJoinError" class="queue-online-error" role="alert">{{ onlineJoinError }}</p>
              <div class="queue-online-actions">
                <button type="button" @click="onlineJoinAudience === 'SELF' ? closeOnlineJoin() : backToOnlineLookup()">
                  {{ onlineJoinAudience === 'SELF' ? '取消' : '返回查询' }}
                </button>
                <button class="primary" type="button" :disabled="onlineJoinLoading || !onlineJoinCanSubmit"
                  @click="submitOnlineJoin">
                  {{ onlineJoinLoading ? '正在提交' : '完成并加入排队' }}
                </button>
              </div>
              <button v-if="onlineJoinAudience === 'SELF'" class="queue-online-secondary queue-online-other-button"
                type="button" @click="beginOtherOnlineJoin">
                为他人创建线上登记
              </button>
            </div>

            <div v-else-if="onlineJoinStep === 'EXISTING'" class="queue-online-result">
              <span class="queue-online-result-icon is-information" aria-hidden="true">
                <UserRoundCheck :size="23" />
              </span>
              <strong>这名玩家已经有一份有效登记，不能重复加入。</strong>
              <p>当前在{{ existingOnlineRegistrationText() }}。{{ onlineJoinExistingRegistration.online_registration_pending_check_in
                ? '这份线上登记仍需在创建后的 30 分钟内到现场终端完成签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动移除。'
                : '' }}</p>
              <p v-if="onlineJoinExistingRegistration.online_registration_pending_check_in && !onlineJoinProfile.setupComplete">这份玩家资料尚未补全。到场后须先在终端补全资料，才能签到。</p>
              <button class="queue-online-primary" type="button"
                @click="openExistingOnlineRegistration(onlineJoinExistingRegistration)">
                {{ onlineJoinAudience === 'OTHER' ? '查看这份登记' : '查看我的排队' }}
              </button>
            </div>

            <div v-else-if="onlineJoinStep === 'PENDING'" class="queue-online-result" aria-live="polite">
              <span class="queue-online-result-icon" aria-hidden="true">
                <RefreshCw :size="23" class="spinning" />
              </span>
              <strong>{{ onlineJoinTerminalApplied ? '终端已保存，正在同步队列'
                : onlineJoinConfirming ? CONFIRMING_RESULT_TITLE : '正在等待现场终端确认' }}</strong>
              <p>{{ onlineJoinTerminalApplied
                ? '终端已确认创建，正在刷新最新队列位置。'
                : onlineJoinConfirming ? CONFIRMING_REGISTRATION_DETAIL
                  : '请保持页面打开。终端处理完成后，这里会显示最终结果；重复点击不会创建多份登记。' }}</p>
              <p v-if="!onlineJoinProfile?.setupComplete">终端确认创建后，到场时须先补全玩家资料，才能签到。</p>
              <button class="queue-online-secondary" type="button" @click="closeOnlineJoin">在后台等待</button>
            </div>

            <div v-else-if="onlineJoinStep === 'UNKNOWN'" class="queue-online-result is-rejected" aria-live="polite">
              <span class="queue-online-result-icon" aria-hidden="true"><TriangleAlert :size="23" /></span>
              <strong>暂时无法确认登记是否已经提交</strong>
              <p>重新提交不会创建重复的登记。</p>
              <button class="queue-online-primary" type="button" :disabled="onlineJoinLoading" @click="resendOnlineJoin">
                重新提交
              </button>
            </div>

            <div v-else-if="onlineJoinStep === 'REJECTED'" class="queue-online-result is-rejected" aria-live="polite">
              <span class="queue-online-result-icon" aria-hidden="true"><TriangleAlert :size="23" /></span>
              <strong>这次线上登记没有执行</strong>
              <p>{{ onlineJoinResultDetail }}</p>
              <button class="queue-online-primary" type="button" @click="retryRejectedOnlineJoin">
                {{ onlineJoinAudience === 'SELF' ? '重新读取本人资料' : '重新查询' }}
              </button>
            </div>

            <div v-else class="queue-online-result" :class="onlineJoinPostApplyEvent ? 'is-rejected' : 'is-success'" aria-live="polite">
              <span class="queue-online-result-icon" aria-hidden="true">
                <TriangleAlert v-if="onlineJoinPostApplyEvent" :size="23" />
                <CircleCheck v-else :size="24" />
              </span>
              <strong>{{ onlineJoinPostApplyEvent ? eventOutcomeTitle(onlineJoinPostApplyEvent) : '已加入等待顺序' }}</strong>
              <p>{{ onlineJoinPostApplyEvent?.detail || onlineJoinResultDetail }}</p>
              <div v-if="!onlineJoinPostApplyEvent" class="queue-online-check-in-notice">
                <TriangleAlert :size="18" aria-hidden="true" />
                <p>
                  <strong>须在 30 分钟内完成签到</strong>
                  <span>请在现场终端点击本人的登记并选择“已到场”。超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动移除。完成签到后，这份登记在游玩一轮之前不能延后；第一次轮到时未到场，只能移除登记，不能暂缓一次或移至队尾。</span>
                  <span v-if="!onlineJoinProfile?.setupComplete">到场后须先在终端补全玩家资料，才能签到。</span>
                </p>
              </div>
              <button class="queue-online-primary" type="button" @click="closeOnlineJoin">查看队列</button>
            </div>
          </section>
        </div>
      </Transition>

      <Transition name="queue-dialog">
        <div v-if="onlineJoinVisible && onlineJoinClosingWarning" class="queue-detail-backdrop"
          @click.self="onlineJoinClosingWarning = null">
          <section class="queue-confirm-dialog" role="alertdialog" aria-modal="true" :aria-label="CLOSING_WARNING_TITLE">
            <h2>{{ CLOSING_WARNING_TITLE }}</h2>
            <p>{{ onlineJoinClosingWarningText }}</p>
            <div>
              <button type="button" @click="onlineJoinClosingWarning = null">取消</button>
              <button type="button" class="primary" @click="confirmOnlineJoinClosingWarning">{{ CLOSING_WARNING_CONFIRM }}</button>
            </div>
          </section>
        </div>
      </Transition>

      <Transition name="queue-dialog">
        <div v-if="selectedDetail" class="queue-detail-backdrop" @click.self="closeDetail">
          <section class="queue-detail-dialog" role="dialog" aria-modal="true" :aria-label="selectedDetail.title">
            <header class="queue-detail-header">
              <div>
                <!-- The player's own registration has the terminal's pencil beside its heading. -->
                <div v-if="detailRenameOffered(selectedDetail)" class="queue-detail-title-row">
                  <h2>{{ selectedDetail.title }}</h2>
                  <button type="button" :disabled="detailActionSubmitting"
                    :aria-label="REGISTRATION_RENAME_ENTRY_LABEL" :title="REGISTRATION_RENAME_ENTRY_LABEL"
                    @click="openDetailRename">
                    <Pencil :size="16" aria-hidden="true" />
                  </button>
                </div>
                <h2 v-else>{{ selectedDetail.title }}</h2>
                <p v-if="selectedDetail.kind === 'registration'">{{ selectedDetail.locationLabel }}</p>
                <p v-else-if="selectedDetail.kind === 'pairedPreview'">{{ selectedDetail.registration.displayId }}</p>
                <p v-else-if="selectedDetail.kind === 'position'">{{ selectedDetail.isPlaying ? selectedDetail.playingText : positionEstimateText(selectedDetail.estimate, selectedDetail.registrations, selectedDetail.machine, selectedDetail.estimateUncertain) }}</p>
                <p v-else>机台 {{ selectedDetail.machine.id }}·{{ machineGroupName(selectedDetail.machine) }}</p>
              </div>
              <button type="button" aria-label="关闭详情" title="关闭" @click="closeDetail">
                <X :size="20" aria-hidden="true" />
              </button>
            </header>

            <template v-if="selectedDetail.kind === 'pairedPreview'">
              <p class="queue-detail-explanation">{{ pairedPreviewExplanation(selectedDetail) }}</p>
            </template>

            <template v-else-if="selectedDetail.kind === 'machine'">
              <div class="queue-detail-pills">
                <span>{{ machineGameTypeLabel(selectedDetail.machine.configuration) }}</span>
                <span :class="{ 'is-absence': !selectedDetail.machine.operational }">
                  {{ selectedDetail.machine.operational ? '正常使用' : '已停止使用' }}
                </span>
              </div>
              <dl class="queue-detail-metadata">
                <div>
                  <dt>机台编号</dt>
                  <dd>{{ selectedDetail.machine.id }}</dd>
                </div>
                <div>
                  <dt>机台备注</dt>
                  <dd>{{ selectedDetail.machine.configuration.remark || '未填写' }}</dd>
                </div>
                <div v-if="configuredMachineGroups.length > 1">
                  <dt>所属分组</dt>
                  <dd>{{ machineGroupName(selectedDetail.machine) }}</dd>
                </div>
                <div>
                  <dt>机台类型</dt>
                  <dd>{{ machineGameTypeLabel(selectedDetail.machine.configuration) }}</dd>
                </div>
                <div v-if="machineServerLabel(selectedDetail.machine.configuration)">
                  <dt>服务器</dt>
                  <dd>{{ machineServerLabel(selectedDetail.machine.configuration) }}</dd>
                </div>
                <div v-if="selectedDetail.machine.configuration.gameVersionVisible && selectedDetail.machine.configuration.gameVersion">
                  <dt>游戏版本</dt>
                  <dd>{{ selectedDetail.machine.configuration.gameVersion }}</dd>
                </div>
                <div>
                  <dt>游玩容量</dt>
                  <dd>{{ selectedDetail.machine.configuration.capacity }} 人</dd>
                </div>
                <div>
                  <dt>计划游玩时间</dt>
                  <dd>{{ machineRoundTimeLabel(selectedDetail.machine.configuration) }}</dd>
                </div>
                <div v-if="!selectedDetail.machine.operational">
                  <dt>停止原因</dt>
                  <dd>{{ stopReasonLabel(
                    selectedDetail.machine.stopReason,
                    selectedDetail.machine.stopReasonDetail
                  ) }}</dd>
                </div>
              </dl>
            </template>

            <template v-else-if="selectedDetail.kind === 'position'">
              <div class="queue-detail-pills">
                <span>{{ selectedDetail.registrations.length }} 个登记</span>
                <span v-if="!selectedDetail.machine.operational">
                  机台已停止使用·{{ stopReasonLabel(
                    selectedDetail.machine.stopReason,
                    selectedDetail.machine.stopReasonDetail
                  ) }}
                </span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.temporarilyAway)" class="is-absence">包含暂时离开</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.skipsOneTurn)" class="is-absence">包含将轮空一次</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.deferredOnce)" class="is-absence">包含将暂缓一次</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.noShowCount > 0)" class="is-warning">包含未到场记录</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.onlineRegistrationPendingCheckIn)" class="is-online">包含待签到登记</span>
                <span v-if="selectedDetail.commonPlayPreview">预计与“{{ selectedDetail.commonPlayPreview.displayId }}”共同游玩</span>
              </div>
              <div class="queue-detail-registration-list">
                <button v-for="registration in selectedDetail.registrations"
                  :key="registration.registrationId || registration.displayId" type="button"
                  :class="[`is-${registrationTone(registration)}`, { 'is-announced': registrationIsAnnounced(registration, selectedDetail.machine), 'is-passing': registrationPassingText(registration, selectedDetail.machine) }]"
                  @click="openRegistrationFromPosition(registration)">
                  <span>
                    <strong>{{ registration.displayId }}</strong>
                    <small>{{ registrationLabel(registration, selectedDetail.machine) }}</small>
                  </span>
                  <UserRoundCheck v-if="isMarkedRegistration(registration)" :size="16" aria-label="我的登记" />
                  <ChevronRight v-else :size="17" aria-hidden="true" />
                </button>
                <div v-if="selectedDetail.commonPlayPreview" class="queue-detail-preview">
                  <span>
                    <strong>{{ selectedDetail.commonPlayPreview.displayId }}</strong>
                    <small>共同游玩预览</small>
                  </span>
                </div>
              </div>
              <p v-if="!selectedDetail.isPlaying && positionEstimateUncertain(selectedDetail.estimate, selectedDetail.registrations, selectedDetail.machine, selectedDetail.estimateUncertain)" class="queue-detail-estimate-note">
                {{ uncertainEstimateNote('这个位置', selectedDetail.estimateUpper) }}
              </p>
              <p v-if="earlierThanAheadNote(selectedDetail)" class="queue-detail-estimate-note">
                {{ earlierThanAheadNote(selectedDetail) }}
              </p>
              <p v-if="positionOnlineMissingNote(selectedDetail)" class="queue-detail-estimate-note">
                {{ positionOnlineMissingNote(selectedDetail) }}
              </p>
            </template>

            <template v-else>
              <div class="queue-detail-pills">
                <span v-if="selectedDetail.registration.onlineRegistrationPendingCheckIn" class="is-online">
                  线上登记·待签到
                </span>
                <span v-else-if="selectedDetail.registration.onlineRegistrationCheckedIn" class="is-online">
                  {{ ONLINE_REGISTRATION_CHECKED_IN_FACT }}
                </span>
                <span :class="{ 'is-absence': absenceLabel(selectedDetail.registration) }">
                  {{ absenceLabel(selectedDetail.registration) || preferencePillLabel(selectedDetail.registration) }}
                </span>
                <span>{{ registrationTypeLabel(selectedDetail.registration) }}</span>
                <span v-if="registrationPartnerText(selectedDetail)">
                  {{ registrationPartnerText(selectedDetail) }}
                </span>
                <span v-if="selectedDetail.registration.alsoQueuesAtPairedMachine && !selectedDetail.registration.skipsPairedMachineThisRound && selectedDetail.machine.pairedMachineId">
                  {{ PAIRED_QUEUE_PILL }}
                </span>
                <span v-if="selectedDetail.registration.noShowCount > 0" class="is-warning">
                  未到场 {{ selectedDetail.registration.noShowCount }} 次
                </span>
              </div>
              <p v-if="selectedDetail.registration.onlineRegistrationPendingCheckIn" class="queue-detail-check-in">
                {{ detailCheckInLimitText(selectedDetail) }}
              </p>
              <p v-if="detailArrangementSentence(selectedDetail)" class="queue-detail-arrangement">
                {{ detailArrangementSentence(selectedDetail) }}
              </p>
              <!-- Only to the player of this registration, who can act on it (maintainer, 2026-11-27). -->
              <p v-if="selectedDetail.registration.skipsOneTurn && accountQueueRegistrationFor(selectedDetail.registration) && skipOneTurnLimitNotice(selectedDetail.registration.temporaryAwaySkippedTurns)" class="queue-detail-skip-limit">
                {{ skipOneTurnLimitNotice(selectedDetail.registration.temporaryAwaySkippedTurns) }}
              </p>
              <p v-if="detailSoloPassParts(selectedDetail)" class="queue-detail-passing">{{ detailSoloPassParts(selectedDetail).before }}<span class="queue-arrangement-highlight">{{ detailSoloPassParts(selectedDetail).highlighted }}</span>{{ detailSoloPassParts(selectedDetail).after }}</p>
              <dl class="queue-detail-metadata">
                <div>
                  <dt>本次游玩偏好</dt>
                  <dd>{{ preferenceLabel(selectedDetail.registration) }}</dd>
                </div>
                <div v-if="absenceLabel(selectedDetail.registration)">
                  <dt>当前状态</dt>
                  <dd>{{ absenceLabel(selectedDetail.registration) }}</dd>
                </div>
                <div v-if="selectedDetail.registration.onlineRegistrationPendingCheckIn">
                  <dt>当前状态</dt>
                  <dd>等待现场签到</dd>
                </div>
                <div v-if="selectedDetail.estimatedWaitMinutes !== null || selectedDetail.registration.onlineRegistrationPendingCheckIn">
                  <dt>预计游玩</dt>
                  <dd>{{ positionEstimateText(selectedDetail.estimatedWaitMinutes, selectedDetail.locationRegistrations, selectedDetail.machine, selectedDetail.estimateUncertain) }}</dd>
                </div>
                <div v-if="selectedDetail.registration.noShowCount > 0">
                  <dt>未到场处理</dt>
                  <dd>{{ noShowResultLabel(selectedDetail.registration) }}</dd>
                </div>
                <div v-if="selectedDetail.registration.qqNumber">
                  <dt>QQ</dt>
                  <dd>{{ selectedDetail.registration.qqNumber }}</dd>
                </div>
                <div v-if="!selectedDetail.machine.operational">
                  <dt>机台状态</dt>
                  <dd>停止使用·{{ stopReasonLabel(
                    selectedDetail.machine.stopReason,
                    selectedDetail.machine.stopReasonDetail
                  ) }}</dd>
                </div>
                <div>
                  <dt>创建时间</dt>
                  <dd>{{ registrationTimeText(selectedDetail.registration.createdAt, '尚无记录') }}</dd>
                </div>
                <!-- Left out in the playing position, where 尚未游玩 would contradict the screen. -->
                <div v-if="!selectedDetail.isPlaying">
                  <dt>上次游玩</dt>
                  <dd>{{ registrationTimeText(selectedDetail.registration.lastPlayedAt, '尚未游玩') }}</dd>
                </div>
              </dl>
              <p v-if="positionEstimateUncertain(selectedDetail.estimatedWaitMinutes, selectedDetail.locationRegistrations, selectedDetail.machine, selectedDetail.estimateUncertain)" class="queue-detail-estimate-note">
                {{ uncertainEstimateNote('这份登记', selectedDetail.estimateUpper) }}
              </p>
              <p v-if="earlierThanAheadNote(selectedDetail)" class="queue-detail-estimate-note">
                {{ earlierThanAheadNote(selectedDetail) }}
              </p>
              <p v-if="registrationOnlineMissingNote(selectedDetail)" class="queue-detail-estimate-note">
                {{ registrationOnlineMissingNote(selectedDetail) }}
              </p>
              <template v-if="accountSessionActive && isAccountRegistration(selectedDetail.registration)">
                <section v-if="accountQueueRegistrationFor(selectedDetail.registration)" class="queue-detail-account-actions">
                  <div class="queue-detail-action-heading">
                    <div>
                      <strong>登记操作</strong>
                      <span>与现场终端保持一致，提交后由终端按最新状态确认</span>
                    </div>
                  </div>
                  <p v-if="!playerAccountQueueState.queue?.terminal_online" class="queue-detail-action-warning">
                    现场终端暂时离线，当前只能查看状态。
                  </p>
                  <p v-else-if="accountQueueRegistrationFor(selectedDetail.registration).machine_operational === false" class="queue-detail-action-warning">
                    {{ STOPPED_MACHINE_ACTION_NOTICE }}
                  </p>
                  <p v-else-if="!playerAccountQueueState.queue?.remote_actions" class="queue-detail-action-warning">
                    现场未开启网页端远程操作，当前只能查看状态。
                  </p>
                  <p v-if="accountPairedQueueStatusParts(accountQueueRegistrationFor(selectedDetail.registration))" class="queue-detail-action-detail">
                    {{ accountPairedQueueStatusParts(accountQueueRegistrationFor(selectedDetail.registration)).before }}<span class="queue-arrangement-highlight">{{ accountPairedQueueStatusParts(accountQueueRegistrationFor(selectedDetail.registration)).highlighted }}</span>{{ accountPairedQueueStatusParts(accountQueueRegistrationFor(selectedDetail.registration)).after }}
                  </p>
                  <template v-if="detailActionMode === 'transfer'">
                    <!-- One row per machine, as on the terminal: what waits there and the wait after
                      moving; a machine that cannot take the registration is grey and says why. -->
                    <template v-if="detailOtherMachines(accountQueueRegistrationFor(selectedDetail.registration)).length > 1">
                    <strong class="queue-detail-action-title">{{ TRANSFER_CHOOSER_TITLE }}</strong>
                    <div class="queue-detail-transfer-rows">
                      <button v-for="row in detailTransferRows(accountQueueRegistrationFor(selectedDetail.registration))" :key="row.id" type="button"
                        :disabled="detailActionSubmitting" :aria-disabled="row.reason ? 'true' : 'false'"
                        :class="{ active: detailActionTargetMachineId === row.id, 'is-unavailable': row.reason }"
                        @click="chooseDetailTransferTarget(row)">
                        <span class="queue-detail-transfer-row-main">
                          <strong>{{ row.name }}</strong>
                          <small>{{ row.description }}</small>
                        </span>
                        <span v-if="!row.reason" class="queue-detail-transfer-row-wait">
                          <small>{{ TRANSFER_WAIT_LABEL }}</small>
                          <strong>{{ row.wait }}</strong>
                        </span>
                      </button>
                    </div>
                    <p v-if="accountQueueRegistrationFor(selectedDetail.registration).fixed_pair && playerAccountQueueState.queue.machines.some((machine) => machine.id !== accountQueueRegistrationFor(selectedDetail.registration).machine_id && machine.available && machine.capacity === 1)"
                      class="queue-detail-action-note">{{ TRANSFER_FIXED_PAIR_NOTE }}</p>
                    </template>
                    <template v-if="detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration))">
                      <strong class="queue-detail-action-title">{{ detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration)).title }}</strong>
                      <p class="queue-detail-action-detail">{{ detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration)).details[0] }}</p>
                      <p class="queue-detail-action-warning-red">{{ detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration)).warning }}</p>
                      <p v-for="line in detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration)).details.slice(1)" :key="line" class="queue-detail-action-detail">{{ line }}</p>
                    </template>
                    <p v-if="detailTransferTargetReason(accountQueueRegistrationFor(selectedDetail.registration))" class="queue-detail-action-note">
                      {{ detailTransferTargetReason(accountQueueRegistrationFor(selectedDetail.registration)) }}
                    </p>
                    <div class="queue-detail-action-confirm">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                      <button class="primary" type="button"
                        :disabled="!detailActionTargetMachineId || detailActionSubmitting || Boolean(detailTransferTargetReason(accountQueueRegistrationFor(selectedDetail.registration)))"
                        @click="submitDetailTransfer(accountQueueRegistrationFor(selectedDetail.registration))">
                        {{ detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration))?.confirm || '确认转至其他机台' }}
                      </button>
                    </div>
                  </template>
                  <template v-else-if="detailActionMode === 'preference'">
                    <strong class="queue-detail-action-title">{{ preferenceChooserText(accountQueueRegistrationFor(selectedDetail.registration)).title }}</strong>
                    <p class="queue-detail-action-detail">{{ preferenceChooserText(accountQueueRegistrationFor(selectedDetail.registration)).intro }}</p>
                    <p v-if="preferenceChooserText(accountQueueRegistrationFor(selectedDetail.registration)).fixedPairNote" class="queue-detail-action-note">{{ preferenceChooserText(accountQueueRegistrationFor(selectedDetail.registration)).fixedPairNote }}</p>
                    <!-- The pair's own tick box, while the pair is kept: a chosen preference releases it. -->
                    <SoloPassTickBox v-if="detailPairPassingOffered(accountQueueRegistrationFor(selectedDetail.registration)) && !detailActionPreference"
                      v-model="detailActionPairPassing" fixed-pair class="queue-detail-pair-passing" :disabled="detailActionSubmitting"
                      :explanation-shown="playerAccount?.profile?.passing_introduced === true"
                      :explanation-key="playerAccount?.profile?.profile_id || ''" />
                    <PreferenceOptions v-if="accountQueueRegistrationFor(selectedDetail.registration).machine_capacity > 1" v-model="detailActionPreference"
                      v-model:passing="detailActionPassing" :passing-available="detailPassingOffered(accountQueueRegistrationFor(selectedDetail.registration))"
                      :disabled="detailActionSubmitting"
                      :passing-explanation-shown="playerAccount?.profile?.passing_introduced === true"
                      :passing-explanation-key="playerAccount?.profile?.profile_id || ''" />
                    <p v-else class="queue-detail-action-detail">{{ preferenceChooserText(accountQueueRegistrationFor(selectedDetail.registration)).singleMachineNote }}</p>
                    <PairedQueueTickBox v-if="accountPairedMachineName(accountQueueRegistrationFor(selectedDetail.registration))"
                      v-model="detailActionPairedQueue" :disabled="detailActionSubmitting"
                      :paired-machine-name="accountPairedMachineName(accountQueueRegistrationFor(selectedDetail.registration))"
                      :fixed-pair="accountQueueRegistrationFor(selectedDetail.registration).fixed_pair === true"
                      :takes-effect-after-a-round="!accountQueueRegistrationFor(selectedDetail.registration).also_queues_at_paired_machine && accountQueueRegistrationFor(selectedDetail.registration).position !== 'PLAYING'"
                      :explanation-shown="playerAccount?.profile?.paired_machine_queueing_introduced === true"
                      :explanation-key="playerAccount?.profile?.profile_id || ''" />
                    <!-- Apart from the choices above, as on the terminal: it opens its own steps. -->
                    <template v-if="detailFriendPairOffered(accountQueueRegistrationFor(selectedDetail.registration))">
                      <p class="queue-detail-action-group">{{ FRIEND_PAIR_SECTION_LABEL }}</p>
                      <button type="button" class="queue-detail-friend-pair-entry"
                        :class="{ 'is-unavailable': detailFriendPairEntry(accountQueueRegistrationFor(selectedDetail.registration)).reason }"
                        :aria-disabled="detailFriendPairEntry(accountQueueRegistrationFor(selectedDetail.registration)).reason ? 'true' : 'false'"
                        :disabled="detailActionSubmitting"
                        @click="requestDetailFriendPair(accountQueueRegistrationFor(selectedDetail.registration))">
                        <span>
                          <strong>{{ detailFriendPairEntry(accountQueueRegistrationFor(selectedDetail.registration)).title }}</strong>
                          <small>{{ detailFriendPairEntry(accountQueueRegistrationFor(selectedDetail.registration)).description }}</small>
                        </span>
                        <ChevronRight v-if="!detailFriendPairEntry(accountQueueRegistrationFor(selectedDetail.registration)).reason" :size="16" aria-hidden="true" />
                      </button>
                    </template>
                    <div class="queue-detail-action-confirm">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                      <button class="primary" type="button"
                        :disabled="detailActionSubmitting || detailPreferenceChangeIsEmpty(accountQueueRegistrationFor(selectedDetail.registration))"
                        @click="confirmDetailPreferenceChange(accountQueueRegistrationFor(selectedDetail.registration))">
                        确认更改游玩偏好
                      </button>
                    </div>
                  </template>
                  <!-- The second question of a fixed-pair member that chose a preference, as on the
                    terminal; 返回 goes back to 更改游玩偏好 with the choices kept. -->
                  <template v-else-if="detailActionMode === 'release_pair'">
                    <strong class="queue-detail-action-title">{{ detailReleasePairPrompt(accountQueueRegistrationFor(selectedDetail.registration)).title }}</strong>
                    <p class="queue-detail-action-detail">{{ detailReleasePairPrompt(accountQueueRegistrationFor(selectedDetail.registration)).detail }}</p>
                    <div class="queue-detail-action-confirm">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = 'preference'">返回</button>
                      <button class="primary" type="button"
                        :disabled="detailActionSubmitting || !preferenceChangeReleasesFixedPair(accountQueueRegistrationFor(selectedDetail.registration), detailActionPreference)"
                        @click="submitDetailPreferenceChange(accountQueueRegistrationFor(selectedDetail.registration))">
                        {{ detailReleasePairPrompt(accountQueueRegistrationFor(selectedDetail.registration)).confirm }}
                      </button>
                    </div>
                  </template>
                  <!-- 与朋友共同游玩: the friend's registration, then the confirmation; 返回 goes back
                    to 更改游玩偏好. -->
                  <FriendPairFlow v-else-if="detailActionMode === 'friend_pair'" class="queue-detail-action-flow"
                    :rows="friendPairCandidateRows(accountQueueRegistrationFor(selectedDetail.registration), queuePageRegistrations)"
                    :disabled="detailActionSubmitting"
                    :passing-offered="detailNewPairPassingOffered(accountQueueRegistrationFor(selectedDetail.registration))"
                    :passing-explanation-shown="playerAccount?.profile?.passing_introduced === true"
                    :passing-explanation-key="playerAccount?.profile?.profile_id || ''"
                    @back="detailActionMode = 'preference'"
                    @confirm="(choice) => submitDetailFriendPair(accountQueueRegistrationFor(selectedDetail.registration), choice)" />
                  <RegistrationRenameForm v-else-if="detailActionMode === 'rename'" class="queue-detail-action-flow"
                    :registration="accountQueueRegistrationFor(selectedDetail.registration)"
                    :page-registrations="queuePageRegistrations" :disabled="detailActionSubmitting"
                    @cancel="detailActionMode = null"
                    @save="(displayId) => submitDetailRename(accountQueueRegistrationFor(selectedDetail.registration), displayId)" />
                  <template v-else-if="detailActionMode === 'postpone'">
                    <PostponeChoiceCards v-bind="postponeChooserHeading(accountQueueRegistrationFor(selectedDetail.registration))"
                      :cards="postponeChoiceCards(playerAccountQueueState.queue?.queue_rules, (mode) => detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), mode))"
                      :disabled="detailActionSubmitting" @choose="requestDetailAction" />
                    <div class="queue-detail-action-confirm is-single">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                    </div>
                  </template>
                  <template v-else-if="[...POSTPONE_CONFIRMATION_MODES, 'leave'].includes(detailActionMode)">
                    <template v-if="detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode)">
                      <strong class="queue-detail-action-title">{{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).title }}</strong>
                      <p class="queue-detail-action-detail">{{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).detail }}</p>
                      <!-- The turns already missed, told before confirming as on the terminal. -->
                      <p v-if="detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).limitNotice" class="queue-detail-action-limit">
                        {{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).limitNotice }}
                      </p>
                      <p v-if="detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).note" class="queue-detail-action-note">
                        {{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).note }}
                      </p>
                    </template>
                    <div class="queue-detail-action-confirm">
                      <button v-if="POSTPONE_CONFIRMATION_MODES.includes(detailActionMode)" type="button" :disabled="detailActionSubmitting" @click="detailActionMode = 'postpone'">返回</button>
                      <button v-else type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                      <button class="primary" :class="{ 'is-danger': detailActionMode === 'leave' }" type="button"
                        :disabled="detailActionSubmitting || !detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode)"
                        @click="submitDetailQueueAction(accountQueueRegistrationFor(selectedDetail.registration), detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).operation, detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).extra || {})">
                        {{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode)?.confirm }}
                      </button>
                    </div>
                  </template>
                  <!-- Grouped as on the terminal's registration menu (docs/ui-guide.md, 菜单的分组).
                    An action that cannot be used is grey and says why under its name, as on the
                    terminal; the entry for moving machines is always there outside the playing
                    position. -->
                  <div v-else class="queue-detail-action-groups">
                    <template v-if="!accountQueueRegistrationFor(selectedDetail.registration).online_registration_pending_check_in &&
                      (accountQueueRegistrationFor(selectedDetail.registration).machine_capacity > 1 || accountPairedMachineName(accountQueueRegistrationFor(selectedDetail.registration)) || accountQueueRegistrationFor(selectedDetail.registration).position !== 'PLAYING')">
                      <p class="queue-detail-action-group">游玩方式</p>
                      <div class="queue-detail-action-buttons">
                        <button v-if="accountQueueRegistrationFor(selectedDetail.registration).machine_capacity > 1 || accountPairedMachineName(accountQueueRegistrationFor(selectedDetail.registration))" type="button"
                          :disabled="detailActionSubmitting"
                          :class="{ 'is-unavailable': detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'preference') }" @click="openDetailPreferenceAction(accountQueueRegistrationFor(selectedDetail.registration))">
                          <span>更改游玩偏好</span>
                          <small v-if="detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'preference')">{{ detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'preference') }}</small>
                        </button>
                        <button v-if="accountQueueRegistrationFor(selectedDetail.registration).position !== 'PLAYING'" type="button"
                          :disabled="detailActionSubmitting"
                          :class="{ 'is-unavailable': detailTransferUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration)) }" @click="requestDetailTransfer(accountQueueRegistrationFor(selectedDetail.registration))">
                          <span>{{ transferEntryLabel(detailOtherMachines(accountQueueRegistrationFor(selectedDetail.registration))) }}</span>
                          <small v-if="detailTransferUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration))">{{ detailTransferUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration)) }}</small>
                        </button>
                      </div>
                    </template>
                    <p class="queue-detail-action-group">这份登记</p>
                    <div class="queue-detail-action-buttons">
                      <template v-if="!accountQueueRegistrationFor(selectedDetail.registration).online_registration_pending_check_in">
                        <button v-if="accountQueueRegistrationFor(selectedDetail.registration).deferred_once" type="button"
                          :disabled="detailActionSubmitting"
                          :class="{ 'is-unavailable': detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_defer') }" @click="cancelDetailPostponement('cancel_defer')">
                          <span>取消暂缓一次</span>
                          <small v-if="detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_defer')">{{ detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_defer') }}</small>
                        </button>
                        <button v-else-if="accountQueueRegistrationFor(selectedDetail.registration).skips_one_turn" type="button"
                          :disabled="detailActionSubmitting"
                          :class="{ 'is-unavailable': detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_skip_one_turn') }" @click="cancelDetailPostponement('cancel_skip_one_turn')">
                          <span>取消轮空一次</span>
                          <small v-if="detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_skip_one_turn')">{{ detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_skip_one_turn') }}</small>
                        </button>
                        <button v-else-if="accountQueueRegistrationFor(selectedDetail.registration).temporarily_away" type="button"
                          :disabled="detailActionSubmitting"
                          :class="{ 'is-unavailable': detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_temporary_leave') }" @click="cancelDetailPostponement('cancel_temporary_leave')">
                          <span>取消暂时离开</span>
                          <small v-if="detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_temporary_leave')">{{ detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'cancel_temporary_leave') }}</small>
                        </button>
                        <button v-else-if="accountQueueRegistrationFor(selectedDetail.registration).position !== 'PLAYING'" type="button"
                          :disabled="detailActionSubmitting"
                          :class="{ 'is-unavailable': detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'postpone') }" @click="requestDetailAction('postpone')">
                          <span>延后这份登记</span>
                          <small v-if="detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'postpone')">{{ detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'postpone') }}</small>
                        </button>
                      </template>
                      <button class="is-danger" :class="{ 'is-unavailable': detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'leave') }" type="button"
                        :disabled="detailActionSubmitting" @click="requestDetailAction('leave')">
                        <span>退出排队</span>
                        <small v-if="detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'leave')">{{ detailActionUnavailableReason(accountQueueRegistrationFor(selectedDetail.registration), 'leave') }}</small>
                      </button>
                    </div>
                  </div>
                  <p v-if="detailActionError" class="queue-detail-action-error" role="alert">{{ detailActionError }}</p>
                  <p v-if="detailActionNoticeText" class="queue-detail-action-notice" role="status">{{ detailActionNoticeText }}</p>
                </section>
              </template>
              <button v-if="selectedDetail.registration.registrationId && !accountSessionActive && !isMarkedRegistration(selectedDetail.registration)"
                class="queue-detail-primary" type="button" @click="requestMarkAsSelf(selectedDetail.registration)">
                <UserRoundCheck :size="18" aria-hidden="true" />
                标记为自己
              </button>
              <button v-else-if="!accountSessionActive && isMarkedRegistration(selectedDetail.registration)"
                class="queue-detail-secondary" type="button" @click="clearMarkedSelf(); closeDetail()">
                取消标记为自己
              </button>
              <p v-if="!accountSessionActive" class="queue-detail-privacy">
                标记使用的昵称、QQ 号和登记信息仅保存在此浏览器中。
              </p>
              <p v-else class="queue-detail-privacy">
                已登录玩家资料，打开自己的登记即可查看与终端一致的操作菜单。
              </p>
            </template>
          </section>
        </div>
      </Transition>

      <Transition name="queue-dialog">
        <div v-if="pendingSelfRegistration" class="queue-detail-backdrop"
          @click.self="pendingSelfRegistration = null">
          <section class="queue-confirm-dialog" role="alertdialog" aria-modal="true" aria-label="更换我的登记">
            <h2>更换“我的排队”标记？</h2>
            <p>当前标记的是“{{ markedSelf?.displayId }}”。确认后，将改为跟踪“{{ pendingSelfRegistration.displayId }}”。</p>
            <div>
              <button type="button" @click="pendingSelfRegistration = null">保留原标记</button>
              <button type="button" class="primary" @click="confirmReplaceMarkedSelf">确认更换</button>
            </div>
          </section>
        </div>
      </Transition>
    </Teleport>

    <footer class="queue-footer" :class="{ 'is-error': loadError }" aria-live="polite">
      <span v-if="loadError && hasSnapshot">连接中断，当前显示上次同步结果</span>
      <span v-else-if="hasSnapshot">现场数据每 10 秒自动刷新</span>
    </footer>
  </main>
</template>

<style scoped>
.queue-panel {
  --queue-page: #f5f5f7;
  --queue-card: #ffffff;
  --queue-position: #fafafc;
  --queue-text: #1d1d1f;
  --queue-secondary: #6e6e73;
  --queue-tertiary: #8e8e93;
  --queue-separator: #d2d2d7;
  --queue-blue: #007aff;
  --queue-soft-blue: #eaf3ff;
  --queue-orange: #b85c00;
  --queue-soft-orange: #fff1dc;
  --queue-red: #c9342c;
  --queue-soft-red: #ffefee;
  --queue-online: #087f73;
  --queue-soft-online: #e8f7f4;
  --queue-disabled: #e8e8ed;
  width: 100%;
  max-width: 1040px;
  margin: 0 auto;
  padding: 6px 4px 36px;
  color: var(--queue-text);
}

:global(.maimai-queue-page .VPPage) { background: var(--queue-page, #f5f5f7); }
:global(html.dark .maimai-queue-page .VPPage) { background: #000000; }
:global(html.dark .maimai-queue-page .queue-panel) {
  --queue-page: #000000;
  --queue-card: #1c1c1e;
  --queue-position: #242426;
  --queue-text: #f5f5f7;
  --queue-secondary: #a1a1a6;
  --queue-tertiary: #8e8e93;
  --queue-separator: #38383a;
  --queue-blue: #0a84ff;
  --queue-soft-blue: #142b44;
  --queue-orange: #ffb35c;
  --queue-soft-orange: #3b2b13;
  --queue-red: #ff6961;
  --queue-soft-red: #3b1716;
  --queue-online: #63d8ca;
  --queue-soft-online: #143632;
  --queue-disabled: #2c2c2e;
}

.queue-panel, .queue-panel * { box-sizing: border-box; letter-spacing: 0; }
button { font: inherit; letter-spacing: 0; -webkit-tap-highlight-color: transparent; }
.queue-header { display: flex; margin: 24px 4px 20px; flex-direction: column; gap: 18px; }
.queue-venue {
  display: block;
  margin-bottom: 5px;
  color: var(--queue-secondary);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}

.queue-heading h1 { margin: 0; border: 0; font-size: 34px; font-weight: 660; line-height: 1.15; letter-spacing: 0; }
.queue-heading p { display: flex; margin: 7px 0 0; flex-wrap: wrap; gap: 0; color: var(--queue-secondary); font-size: 13px; line-height: 1.55; }
.queue-heading strong { color: var(--queue-text); font-weight: 560; }
.queue-heading-separator { display: inline-block; width: .24em; height: .24em; margin: .655em .38em; flex: 0 0 .24em; border-radius: 50%; background: var(--queue-tertiary); }
.queue-toolbar { display: flex; min-width: 0; flex-direction: column; gap: 12px; }
.queue-view-tabs { display: grid; width: 100%; padding: 3px; grid-template-columns: 1fr 1fr; border-radius: 10px; background: color-mix(in srgb, var(--queue-separator) 42%, transparent); }
.queue-view-tabs button { display: flex; min-height: 36px; align-items: center; justify-content: center; gap: 6px; border: 0; border-radius: 8px; color: var(--queue-secondary); background: transparent; cursor: pointer; font-size: 12px; transition: color .16s ease, background .16s ease, box-shadow .16s ease; }
.queue-view-tabs button.active { color: var(--queue-text); background: var(--queue-card); box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.queue-system { display: grid; width: 100%; min-width: 0; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; }
.queue-system time { font-size: 16px; font-weight: 570; font-variant-numeric: tabular-nums; }
.queue-system-status { display: flex; min-width: 0; align-items: center; gap: 10px; }
.queue-system-divider { width: 1px; height: 16px; background: var(--queue-separator); }
.queue-availability { display: flex; min-width: 0; align-items: center; gap: 7px; color: var(--queue-secondary); font-size: 12px; line-height: 1.35; }
.queue-availability > span { width: 8px; height: 8px; flex: 0 0 auto; border-radius: 50%; background: #8e8e93; }
.queue-availability.is-closed > span { background: #ff9500; }
.queue-availability.is-outside > span { background: #ff3b30; }
.queue-system-actions { display: flex; align-items: center; gap: 6px; }
.queue-version-button, .queue-refresh { display: grid; width: 40px; height: 40px; padding: 0; place-items: center; border: 1px solid color-mix(in srgb, var(--queue-separator) 72%, transparent); border-radius: 50%; color: var(--queue-text); background: var(--queue-card); cursor: pointer; transition: border-color .16s ease, background .16s ease, transform .12s ease; }
.queue-refresh:disabled { opacity: .55; }
.queue-version-button:active, .queue-refresh:active:not(:disabled) { transform: scale(.95); }
.queue-refresh:focus-visible, button:focus-visible, [role='button']:focus-visible { outline: 2px solid var(--queue-blue); outline-offset: 2px; }
.spinning { animation: queue-spin .8s linear infinite; }
.queue-test-notice { display: flex; margin: 0 0 16px; padding: 10px 12px; align-items: center; gap: 8px; border-left: 3px solid #ff9500; color: var(--queue-orange); background: color-mix(in srgb, var(--queue-soft-orange) 68%, var(--queue-card)); }
.queue-test-notice svg { display: block; flex: 0 0 auto; }
.queue-test-notice strong { font-size: 11px; font-weight: 620; line-height: 1.45; }
.queue-stale-notice { display: flex; margin: 0 0 16px; padding: 11px 12px; align-items: flex-start; gap: 9px; border-left: 3px solid #ff9500; color: var(--queue-orange); background: color-mix(in srgb, var(--queue-soft-orange) 62%, var(--queue-card)); }
.queue-stale-notice svg { display: block; flex: 0 0 auto; margin-top: 1px; }
.queue-stale-notice div, .queue-stale-notice strong, .queue-stale-notice span { display: block; }
.queue-stale-notice strong { font-size: 12px; font-weight: 650; line-height: 1.4; }
.queue-stale-notice span { margin-top: 2px; color: var(--queue-secondary); font-size: 11px; line-height: 1.55; }

.queue-self { display: grid; margin: 0 0 18px; padding: 17px 18px; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: start; gap: 13px; border: 1px solid color-mix(in srgb, var(--queue-blue) 28%, var(--queue-separator)); border-radius: 14px; background: color-mix(in srgb, var(--queue-soft-blue) 72%, var(--queue-card)); }
.queue-self.is-clickable { cursor: pointer; }
.queue-self.is-clickable:focus-visible { outline: 2px solid var(--queue-blue); outline-offset: 2px; }
.queue-self.is-warning { border-color: color-mix(in srgb, #ff9500 36%, var(--queue-separator)); background: color-mix(in srgb, var(--queue-soft-orange) 74%, var(--queue-card)); }
.queue-self.is-danger { border-color: color-mix(in srgb, var(--queue-red) 36%, var(--queue-separator)); background: color-mix(in srgb, var(--queue-soft-red) 76%, var(--queue-card)); }
.queue-self.is-online { border-color: color-mix(in srgb, var(--queue-online) 36%, var(--queue-separator)); background: color-mix(in srgb, var(--queue-soft-online) 78%, var(--queue-card)); }
.queue-self.is-muted { border-color: var(--queue-separator); background: var(--queue-card); }
.queue-self-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 50%; color: var(--queue-blue); background: var(--queue-card); }
.queue-self.is-warning .queue-self-icon { color: var(--queue-orange); }
.queue-self.is-danger .queue-self-icon { color: var(--queue-red); }
.queue-self.is-online .queue-self-icon { color: var(--queue-online); }
.queue-self-main { min-width: 0; }
.queue-self-eyebrow { color: var(--queue-secondary); font-size: 10px; font-weight: 600; }
.queue-self h2 { margin: 1px 0 0; border: 0; font-size: 19px; font-weight: 650; line-height: 1.3; letter-spacing: 0; overflow-wrap: anywhere; }
.queue-self-main > strong { display: block; margin-top: 7px; font-size: 13px; font-weight: 620; line-height: 1.45; }
.queue-self-status-badge { width: fit-content; padding: 4px 7px; border-radius: 6px; color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-self.is-warning .queue-self-status-badge { color: var(--queue-orange); background: var(--queue-soft-orange); }
.queue-self.is-danger .queue-self-status-badge { color: var(--queue-red); background: var(--queue-soft-red); }
.queue-self.is-online .queue-self-status-badge { color: var(--queue-online); background: var(--queue-soft-online); }
.queue-self-main > p { margin: 3px 0 0; color: var(--queue-secondary); font-size: 11px; line-height: 1.6; }
.queue-self-facts { display: flex; margin-top: 9px; flex-wrap: wrap; gap: 6px; }
.queue-self-facts span { display: flex; padding: 4px 7px; align-items: center; gap: 4px; border-radius: 6px; color: var(--queue-secondary); background: color-mix(in srgb, var(--queue-card) 82%, transparent); font-size: 10px; }
.queue-self-clear { padding: 7px 8px; border: 0; color: var(--queue-secondary); background: transparent; cursor: pointer; font-size: 11px; }
.queue-self-account-badge { padding: 5px 7px; border-radius: 6px; color: var(--queue-blue); background: var(--queue-soft-blue); font-size: 10px; font-weight: 600; white-space: nowrap; }
.queue-feedback { margin: 0 0 18px; }
.queue-feedback > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 7px; }
.queue-feedback > header strong { color: var(--queue-secondary); font-size: 10px; font-weight: 650; }
.queue-feedback > header button, .queue-feedback-dismiss { border: 0; color: var(--queue-tertiary); background: transparent; cursor: pointer; font-size: 10px; }
.queue-feedback article { display: flex; margin-top: 6px; padding: 10px 11px; align-items: flex-start; justify-content: space-between; gap: 10px; border-left: 3px solid var(--queue-blue); background: var(--queue-soft-blue); }
.queue-feedback article.is-error, .queue-feedback article.is-unknown { border-color: var(--queue-orange); background: var(--queue-soft-orange); }
.queue-feedback article strong, .queue-feedback article p { display: block; }
.queue-feedback article strong { font-size: 11px; font-weight: 640; }
.queue-feedback article p { margin: 3px 0 0; color: var(--queue-secondary); font-size: 10px; line-height: 1.5; }
.queue-feedback article > div > button { margin-top: 7px; padding: 4px 7px; border: 1px solid var(--queue-separator); border-radius: 6px; color: var(--queue-text); background: var(--queue-card); cursor: pointer; font-size: 10px; }

.queue-online-entry { display: grid; min-height: 66px; margin: 0 0 16px; padding: 11px 12px; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 11px; border: 1px solid color-mix(in srgb, var(--queue-online) 24%, var(--queue-separator)); border-radius: 11px; background: color-mix(in srgb, var(--queue-soft-online) 48%, var(--queue-card)); }
.queue-online-entry.is-disabled { border-color: var(--queue-separator); background: var(--queue-card); }
.queue-online-entry-icon { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 50%; color: var(--queue-online); background: var(--queue-card); line-height: 0; }
.queue-online-entry-icon > svg { display: block; }
.queue-online-entry.is-disabled .queue-online-entry-icon { color: var(--queue-tertiary); background: var(--queue-position); }
.queue-online-entry > div { min-width: 0; }
.queue-online-entry > div > strong, .queue-online-entry > div > span { display: block; }
.queue-online-entry > div > strong { font-size: 13px; font-weight: 620; line-height: 1.4; }
.queue-online-entry > div > span { margin-top: 2px; color: var(--queue-secondary); font-size: 10px; line-height: 1.45; }
.queue-online-entry > button { min-height: 36px; padding: 0 13px; border: 0; border-radius: 8px; color: #fff; background: var(--queue-blue); cursor: pointer; font-size: 11px; font-weight: 600; }
.queue-online-entry > button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }

.queue-unavailable { display: flex; min-height: 184px; padding: 28px 22px; align-items: center; gap: 15px; border: 1px solid var(--queue-separator); border-radius: 14px; background: var(--queue-card); }
.queue-unavailable-icon { display: grid; width: 44px; height: 44px; flex: 0 0 auto; place-items: center; border-radius: 50%; color: var(--queue-secondary); background: var(--queue-position); }
.queue-unavailable h2, .queue-machine-message p { margin: 0; border: 0; font-size: 18px; font-weight: 600; line-height: 1.35; }
.queue-unavailable p, .queue-machine-message span { display: block; margin: 6px 0 0; color: var(--queue-secondary); font-size: 12px; line-height: 1.6; }
.queue-natural-notice { display: flex; margin-bottom: 16px; padding: 13px 15px; align-items: flex-start; gap: 10px; border-left: 3px solid #34c759; color: var(--queue-secondary); background: color-mix(in srgb, #34c759 7%, var(--queue-card)); }
.queue-natural-notice svg { flex: 0 0 auto; color: #248a3d; }
.queue-natural-notice.is-outside { border-left-color: #ff9500; background: color-mix(in srgb, var(--queue-soft-orange) 72%, var(--queue-card)); }
.queue-natural-notice.is-outside svg { color: var(--queue-orange); }
.queue-natural-notice strong, .queue-natural-notice span { display: block; }
.queue-natural-notice strong { color: var(--queue-text); font-size: 13px; }
.queue-natural-notice span { margin-top: 2px; font-size: 11px; line-height: 1.5; }

.queue-machine-groups { display: flex; min-width: 0; margin: 0 0 14px; padding: 3px; overflow-x: auto; gap: 3px; border-radius: 10px; background: color-mix(in srgb, var(--queue-separator) 42%, transparent); scrollbar-width: thin; }
.queue-machine-groups.is-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); overflow: hidden; }
.queue-machine-groups button { display: flex; min-width: 112px; min-height: 42px; padding: 6px 12px; align-items: flex-start; justify-content: center; flex-direction: column; border: 0; border-radius: 8px; color: var(--queue-secondary); background: transparent; cursor: pointer; white-space: nowrap; }
.queue-machine-groups.is-pair button { width: 100%; min-width: 0; align-items: center; text-align: center; }
.queue-machine-groups button.active { color: var(--queue-text); background: var(--queue-card); box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.queue-machine-groups span, .queue-machine-groups small { display: block; }
.queue-machine-groups span, .queue-machine-groups small { width: 100%; overflow: hidden; text-overflow: ellipsis; }
.queue-machine-groups span { font-size: 11px; font-weight: 620; }
.queue-machine-groups small { margin-top: 1px; color: var(--queue-tertiary); font-size: 9px; }
.queue-machine-list { display: grid; gap: 16px; }
.queue-machine-list.is-single { grid-template-columns: minmax(0, 1fr); }
.queue-machine { min-width: 0; padding: 17px; border: 1px solid color-mix(in srgb, var(--queue-separator) 74%, transparent); border-radius: 14px; background: var(--queue-card); }
.queue-machine:only-child { grid-column: 1 / -1; }
.queue-machine-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.queue-machine-header > button { display: flex; min-width: 0; min-height: 48px; margin: -7px 0 -7px -7px; padding: 7px; align-items: center; flex: 1 1 auto; gap: 8px; border: 0; border-radius: 8px; color: var(--queue-text); background: transparent; text-align: left; cursor: pointer; }
.queue-machine-header > button:hover { background: var(--queue-position); }
.queue-machine-header > button > span { display: block; min-width: 0; flex: 1 1 auto; }
.queue-machine-header > button > svg { flex: 0 0 auto; color: var(--queue-tertiary); }
.queue-machine-title, .queue-machine-summary { display: block; }
.queue-machine-title { overflow-wrap: anywhere; font-size: 19px; font-weight: 620; line-height: 1.35; }
.queue-machine-summary { margin-top: 3px; color: var(--queue-secondary); font-size: 11px; line-height: 1.45; }
.queue-machine-state { padding: 5px 8px; flex: 0 0 auto; border-radius: 6px; color: var(--queue-orange); background: var(--queue-soft-orange); font-size: 10px; font-weight: 650; }
.queue-machine-message { display: flex; min-height: 142px; padding: 24px 8px 8px; justify-content: center; flex-direction: column; }
.queue-machine-message.is-stopped { min-height: 0; margin-top: 12px; padding: 12px 13px; border-radius: 9px; background: color-mix(in srgb, var(--queue-soft-orange) 82%, var(--queue-position)); }
.queue-machine-message.is-stopped p { font-size: 13px; }
.queue-machine-message.is-stopped span { margin-top: 3px; }
.queue-position-list { display: grid; margin-top: 14px; gap: 10px; }
.queue-position { min-width: 0; padding: 11px; border: 1px solid var(--queue-separator); border-radius: 11px; background: var(--queue-position); }
.queue-position.is-playing { border-color: color-mix(in srgb, var(--queue-blue) 25%, var(--queue-separator)); background: var(--queue-soft-blue); }
.queue-position.is-actionable { cursor: pointer; transition: border-color .16s ease, background .16s ease, transform .14s ease; -webkit-tap-highlight-color: transparent; }
.queue-position.is-actionable:hover { border-color: color-mix(in srgb, var(--queue-blue) 42%, var(--queue-separator)); }
.queue-position.is-actionable:active { transform: scale(.997); }
.queue-position > header { display: flex; min-height: 25px; align-items: flex-start; justify-content: space-between; gap: 8px; }
.queue-position > header > span, .queue-position-heading > span { color: var(--queue-tertiary); font-size: 11px; font-weight: 580; line-height: 1.4; }
.queue-position.is-playing > header > span { color: var(--queue-blue); }
.queue-position > header > svg { margin-top: -1px; flex: 0 0 auto; color: var(--queue-tertiary); }
.queue-position-heading { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.queue-position-heading small { color: var(--queue-secondary); font-size: 10px; font-weight: 450; line-height: 1.45; }
.queue-position-empty { display: grid; min-height: 72px; place-items: center; color: var(--queue-tertiary); font-size: 12px; }
.queue-registration-grid { display: grid; min-width: 0; margin-top: 6px; grid-template-columns: repeat(auto-fit, minmax(min(96px, 100%), 1fr)); gap: 7px; }
/* Each tile asks for --tile-width, the longer of its two lines with the nickname counted up to
   six characters (registrationTileWidth); tiles that do not fit side by side take a row each
   (usability U5). Beside the overtime notice of the playing position the grid stays, with
   columns wide enough for six characters. */
.queue-registration-grid:not(:has(> .queue-overtime)) { display: flex; flex-wrap: wrap; }
.queue-registration-grid:not(:has(> .queue-overtime)) > .queue-registration { flex: 1 1 var(--tile-width, 96px); min-width: min(var(--tile-width, 96px), 100%); }
.queue-registration-grid:has(> .queue-overtime) { grid-template-columns: repeat(auto-fit, minmax(min(124px, 100%), 1fr)); }
.queue-overtime:only-child { grid-column: 1 / -1; }
.queue-registration, .queue-overtime { min-width: 0; min-height: 70px; border-radius: 8px; background: var(--queue-card); }
.queue-registration { position: relative; display: flex; padding: 10px 28px 10px 10px; justify-content: center; flex-direction: column; border: 1px solid transparent; color: inherit; text-align: left; cursor: pointer; transition: background .16s ease, border-color .16s ease, transform .12s ease; -webkit-tap-highlight-color: transparent; }
.queue-registration:hover { background: color-mix(in srgb, var(--queue-soft-blue) 50%, var(--queue-card)); }
.queue-registration:active { transform: scale(.985); }
.queue-position.is-playing .queue-registration { border-color: color-mix(in srgb, var(--queue-blue) 8%, transparent); background: color-mix(in srgb, var(--queue-card) 90%, var(--queue-soft-blue)); }
.queue-registration.is-absence { background: color-mix(in srgb, var(--queue-soft-orange) 72%, var(--queue-card)); }
.queue-registration.is-warning { border-color: color-mix(in srgb, var(--queue-red) 22%, transparent); background: color-mix(in srgb, var(--queue-soft-red) 72%, var(--queue-card)); }
.queue-registration.is-online { border-color: color-mix(in srgb, var(--queue-online) 22%, transparent); background: color-mix(in srgb, var(--queue-soft-online) 76%, var(--queue-card)); }
.queue-registration.is-preview { padding-right: 10px; color: var(--queue-secondary); border-color: color-mix(in srgb, var(--queue-tertiary) 18%, transparent); background: var(--queue-disabled); cursor: default; }
.queue-registration.is-preview:hover { background: var(--queue-disabled); }
.queue-registration.is-preview:active { transform: none; }
.queue-registration.is-preview span { color: var(--queue-tertiary); }
.queue-registration.is-paired-preview { padding-right: 28px; cursor: pointer; }
.queue-detail-explanation { margin: 16px 0 0; color: var(--queue-secondary); font-size: 13px; line-height: 1.75; white-space: pre-line; }
.queue-registration.is-self { border-color: color-mix(in srgb, var(--queue-blue) 54%, var(--queue-separator)); }
.queue-registration > svg { position: absolute; top: 50%; right: 8px; color: var(--queue-tertiary); transform: translateY(-50%); }
.queue-registration.is-self > svg { color: var(--queue-blue); }
.queue-registration strong { overflow: hidden; font-size: 14px; font-weight: 570; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.queue-registration span { margin-top: 4px; color: var(--queue-secondary); font-size: 10px; line-height: 1.35; }
.queue-registration.is-absence span { color: var(--queue-orange); font-weight: 570; }
.queue-registration.is-warning span { color: var(--queue-red); font-weight: 570; }
.queue-registration.is-online span { color: var(--queue-online); font-weight: 570; }
.queue-registration.is-announced span { color: var(--queue-blue); font-weight: 570; }
.queue-registration.is-passing span { color: var(--queue-blue); font-weight: 570; }
.queue-overtime { display: flex; padding: 10px; justify-content: center; flex-direction: column; color: var(--queue-orange); background: var(--queue-soft-orange); }
.queue-overtime strong { font-size: 11px; line-height: 1.4; }
.queue-overtime span { margin-top: 4px; font-size: 9px; line-height: 1.45; }

.queue-logs { padding: 17px; border: 1px solid var(--queue-separator); border-radius: 14px; background: var(--queue-card); }
.queue-logs-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; }
.queue-logs-header h2 { margin: 0; border: 0; font-size: 20px; font-weight: 630; letter-spacing: 0; }
.queue-logs-header p { margin: 4px 0 0; color: var(--queue-secondary); font-size: 11px; line-height: 1.5; }
.queue-log-filter-groups { display: grid; width: 100%; min-width: 0; margin-top: 13px; gap: 6px; }
.queue-log-filters { display: flex; width: 100%; min-width: 0; padding: 3px; gap: 0; overflow: hidden; border-radius: 9px; background: var(--queue-position); }
.queue-log-filters button { min-width: 0; min-height: 30px; padding: 0 7px; flex: 1 1 0; overflow: hidden; border: 0; border-radius: 7px; color: var(--queue-secondary); background: transparent; cursor: pointer; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; transition: color .16s ease, background .16s ease, box-shadow .16s ease; }
.queue-log-filters button.active { color: var(--queue-text); background: var(--queue-card); box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.queue-log-list { margin: 17px 0 0; padding: 0; list-style: none; border-top: 1px solid var(--queue-separator); }
.queue-log-list li { display: grid; padding: 14px 2px; grid-template-columns: 112px minmax(0, 1fr); gap: 18px; border-bottom: 1px solid var(--queue-separator); }
.queue-log-list li.is-self { margin: 0 -9px; padding-right: 11px; padding-left: 11px; border-radius: 8px; background: var(--queue-soft-blue); }
.queue-log-time time, .queue-log-time span { display: block; }
.queue-log-time time { color: var(--queue-secondary); font-size: 10px; font-variant-numeric: tabular-nums; line-height: 1.45; }
.queue-log-time span { margin-top: 3px; color: var(--queue-tertiary); font-size: 9px; }
.queue-log-body { min-width: 0; }
.queue-log-title { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.queue-log-title strong { font-size: 13px; font-weight: 610; line-height: 1.4; }
.queue-log-title span, .queue-log-title em { padding: 3px 6px; border-radius: 5px; color: var(--queue-secondary); background: var(--queue-position); font-size: 9px; font-style: normal; line-height: 1.3; }
.queue-log-title em { color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-log-body p { margin: 5px 0 0; color: var(--queue-secondary); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.queue-logs-empty { display: flex; min-height: 210px; padding: 28px; align-items: center; justify-content: center; flex-direction: column; gap: 7px; color: var(--queue-tertiary); text-align: center; }
.queue-logs-empty strong { color: var(--queue-text); font-size: 14px; }
.queue-logs-empty span { max-width: 420px; color: var(--queue-secondary); font-size: 11px; line-height: 1.6; }
.queue-logs-empty.is-error svg { color: var(--queue-orange); }
.queue-logs-empty button, .queue-load-more { min-height: 36px; margin-top: 6px; padding: 0 13px; border: 1px solid var(--queue-separator); border-radius: 8px; color: var(--queue-text); background: var(--queue-card); cursor: pointer; font-size: 11px; }
.queue-load-more { display: block; margin: 14px auto 0; }

.queue-detail-backdrop { position: fixed; z-index: 10000; inset: 0; display: grid; padding: 18px; place-items: center; background: rgba(0, 0, 0, .42); }
.queue-detail-dialog, .queue-confirm-dialog { --queue-card: #fff; --queue-position: #f5f5f7; --queue-text: #1d1d1f; --queue-secondary: #6e6e73; --queue-tertiary: #8e8e93; --queue-separator: #d2d2d7; --queue-blue: #007aff; --queue-soft-blue: #eaf3ff; --queue-orange: #b85c00; --queue-soft-orange: #fff1dc; --queue-red: #c9342c; --queue-soft-red: #ffefee; --queue-online: #087f73; --queue-soft-online: #e8f7f4; width: min(100%, 480px); max-height: min(680px, calc(100vh - 36px)); padding: 20px; overflow-y: auto; border: 1px solid var(--queue-separator); border-radius: 16px; color: var(--queue-text); background: var(--queue-card); box-shadow: 0 20px 54px rgba(0, 0, 0, .22); }
:global(html.dark .queue-detail-dialog), :global(html.dark .queue-confirm-dialog) { --queue-card: #1c1c1e; --queue-position: #242426; --queue-text: #f5f5f7; --queue-secondary: #a1a1a6; --queue-tertiary: #8e8e93; --queue-separator: #38383a; --queue-blue: #0a84ff; --queue-soft-blue: #142b44; --queue-orange: #ffb35c; --queue-soft-orange: #3b2b13; --queue-red: #ff6961; --queue-soft-red: #3b1716; --queue-online: #63d8ca; --queue-soft-online: #143632; }
.queue-detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.queue-detail-header > div { min-width: 0; }
.queue-detail-header h2, .queue-confirm-dialog h2 { margin: 0; border: 0; overflow-wrap: anywhere; font-size: 22px; font-weight: 640; line-height: 1.3; letter-spacing: 0; }
.queue-detail-header p { margin: 4px 0 0; color: var(--queue-secondary); font-size: 12px; }
.queue-detail-header > button { display: grid; width: 38px; height: 38px; padding: 0; flex: 0 0 auto; place-items: center; border: 0; border-radius: 50%; color: var(--queue-secondary); background: var(--queue-position); cursor: pointer; }
.queue-detail-pills { display: flex; margin-top: 16px; flex-wrap: wrap; gap: 7px; }
.queue-detail-pills span { padding: 5px 8px; border-radius: 6px; color: var(--queue-secondary); background: var(--queue-position); font-size: 10px; line-height: 1.35; }
.queue-detail-pills span.is-absence { color: var(--queue-orange); background: var(--queue-soft-orange); }
.queue-detail-pills span.is-warning { color: var(--queue-red); background: var(--queue-soft-red); }
.queue-detail-pills span.is-online { color: var(--queue-online); background: var(--queue-soft-online); }
.queue-detail-arrangement { margin: 12px 0 0; padding: 9px 11px; border-radius: 8px; color: var(--queue-blue); background: var(--queue-soft-blue); font-size: 12px; font-weight: 570; line-height: 1.6; }
.queue-arrangement-highlight { color: var(--queue-blue); font-weight: 570; }
.queue-detail-check-in { margin: 12px 0 0; padding: 9px 11px; border-radius: 8px; color: var(--queue-online); background: var(--queue-soft-online); font-size: 12px; line-height: 1.6; }
.queue-detail-estimate-note { margin: 14px 0 0; color: var(--queue-secondary); font-size: 12px; line-height: 1.65; }
.queue-detail-passing { margin: 12px 0 0; color: var(--queue-secondary); font-size: 12px; line-height: 1.6; }
.queue-detail-skip-limit { margin: 12px 0 0; color: var(--queue-orange); font-size: 12px; font-weight: 570; line-height: 1.6; }
.queue-detail-registration-list { display: grid; margin-top: 16px; gap: 8px; }
.queue-detail-registration-list button { display: flex; min-width: 0; min-height: 54px; padding: 10px 12px; align-items: center; justify-content: space-between; gap: 12px; border: 1px solid var(--queue-separator); border-radius: 9px; color: var(--queue-text); background: var(--queue-position); text-align: left; cursor: pointer; transition: border-color .16s ease, background .16s ease, transform .12s ease; }
.queue-detail-preview { display: flex; min-width: 0; min-height: 54px; padding: 10px 12px; align-items: center; border: 1px solid color-mix(in srgb, var(--queue-tertiary) 18%, transparent); border-radius: 9px; color: var(--queue-secondary); background: var(--queue-disabled); }
.queue-detail-preview span, .queue-detail-preview strong, .queue-detail-preview small { display: block; min-width: 0; }
.queue-detail-preview strong { overflow: hidden; font-size: 12px; font-weight: 570; text-overflow: ellipsis; white-space: nowrap; }
.queue-detail-preview small { margin-top: 3px; color: var(--queue-tertiary); font-size: 9px; }
.queue-detail-registration-list button:active { transform: scale(.99); }
.queue-detail-registration-list button.is-absence { border-color: color-mix(in srgb, var(--queue-orange) 18%, var(--queue-separator)); background: color-mix(in srgb, var(--queue-soft-orange) 72%, var(--queue-position)); }
.queue-detail-registration-list button.is-warning { border-color: color-mix(in srgb, var(--queue-red) 18%, var(--queue-separator)); background: color-mix(in srgb, var(--queue-soft-red) 72%, var(--queue-position)); }
.queue-detail-registration-list button.is-online { border-color: color-mix(in srgb, var(--queue-online) 20%, var(--queue-separator)); background: color-mix(in srgb, var(--queue-soft-online) 76%, var(--queue-position)); }
.queue-detail-registration-list button > span { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.queue-detail-registration-list strong { overflow-wrap: anywhere; font-size: 13px; font-weight: 580; }
.queue-detail-registration-list small { color: var(--queue-secondary); font-size: 10px; }
.queue-detail-registration-list button.is-absence small { color: var(--queue-orange); }
.queue-detail-registration-list button.is-warning small { color: var(--queue-red); }
.queue-detail-registration-list button.is-online small { color: var(--queue-online); }
.queue-detail-registration-list button.is-announced small { color: var(--queue-blue); font-weight: 570; }
.queue-detail-registration-list button.is-passing small { color: var(--queue-blue); font-weight: 570; }
.queue-detail-registration-list svg { flex: 0 0 auto; color: var(--queue-tertiary); }
.queue-detail-metadata { margin: 18px 0 0; border-top: 1px solid var(--queue-separator); }
.queue-detail-metadata > div { display: grid; min-height: 44px; padding: 9px 0; grid-template-columns: minmax(82px, auto) minmax(0, 1fr); align-items: center; gap: 16px; border-bottom: 1px solid var(--queue-separator); }
.queue-detail-metadata dt, .queue-detail-metadata dd { margin: 0; font-size: 11px; line-height: 1.5; }
.queue-detail-metadata dt { color: var(--queue-tertiary); }
.queue-detail-metadata dd { overflow-wrap: anywhere; text-align: right; }
.queue-detail-account-actions { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--queue-separator); }
.queue-detail-action-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.queue-detail-action-heading strong, .queue-detail-action-heading span { display: block; }
.queue-detail-action-heading strong { font-size: 13px; font-weight: 620; }
.queue-detail-action-heading span { margin-top: 3px; color: var(--queue-secondary); font-size: 10px; line-height: 1.45; }
.queue-detail-action-warning { margin: 12px 0 0; padding: 9px 10px; border-left: 3px solid var(--queue-orange); color: var(--queue-secondary); background: var(--queue-soft-orange); font-size: 10px; line-height: 1.5; }
.queue-detail-action-buttons { display: grid; margin-top: 12px; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; }
.queue-detail-action-groups .queue-detail-action-buttons { margin-top: 6px; }
.queue-detail-action-group { display: flex; align-items: center; gap: 8px; margin: 12px 0 0; color: var(--queue-secondary); font-size: 10px; font-weight: 620; }
.queue-detail-action-group::after { content: ''; flex: 1; height: 1px; background: var(--queue-separator); }
.queue-detail-action-confirm.is-single { grid-template-columns: 1fr; }
.queue-detail-action-warning-red { margin: 8px 0 0; padding: 9px 10px; border-left: 3px solid var(--queue-red); color: var(--queue-red); background: var(--queue-soft-red); font-size: 10px; line-height: 1.6; }
.queue-detail-action-buttons button, .queue-detail-action-confirm button { min-height: 40px; padding: 7px 9px; border: 1px solid var(--queue-separator); border-radius: 8px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; font-size: 11px; line-height: 1.35; }
.queue-detail-action-buttons button.is-danger:not(:disabled), .queue-detail-action-confirm button.is-danger:not(:disabled) { color: var(--queue-red) !important; }
.queue-detail-action-buttons button.is-unavailable { display: grid; align-content: center; gap: 3px; color: var(--queue-tertiary); background: var(--queue-disabled); text-align: left; }
.queue-detail-action-buttons button small { display: block; color: var(--queue-tertiary); font-size: 10px; font-weight: 400; line-height: 1.5; }
.queue-detail-action-buttons button:disabled, .queue-detail-action-confirm button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-detail-action-title { display: block; margin-top: 13px; font-size: 12px; font-weight: 620; }
.queue-detail-action-detail, .queue-detail-action-note { margin: 7px 0 0; color: var(--queue-secondary); font-size: 10px; line-height: 1.6; }
.queue-detail-action-note { color: var(--queue-orange); }
.queue-detail-pair-passing { margin: 7px 0 6px; }
.queue-detail-action-limit { margin: 7px 0 0; color: var(--queue-orange); font-size: 11px; font-weight: 570; line-height: 1.6; }
.queue-detail-action-flow { margin-top: 13px; }
.queue-detail-friend-pair-entry { display: flex; width: 100%; min-height: 52px; margin-top: 6px; padding: 9px 11px; align-items: center; justify-content: space-between; gap: 12px; border: 1px solid var(--queue-separator); border-radius: 9px; color: var(--queue-text); background: var(--queue-card); cursor: pointer; text-align: left; font: inherit; }
.queue-detail-friend-pair-entry > span { display: grid; min-width: 0; gap: 3px; }
.queue-detail-friend-pair-entry strong { font-size: 12px; font-weight: 620; line-height: 1.4; }
.queue-detail-friend-pair-entry small { color: var(--queue-secondary); font-size: 10px; font-weight: 400; line-height: 1.55; }
.queue-detail-friend-pair-entry > svg { flex: none; color: var(--queue-tertiary); }
.queue-detail-friend-pair-entry.is-unavailable { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-detail-friend-pair-entry.is-unavailable small { color: var(--queue-tertiary); }
.queue-detail-friend-pair-entry:disabled { cursor: default; }
.queue-detail-title-row { display: flex; align-items: flex-start; gap: 2px; }
.queue-detail-title-row h2 { min-width: 0; }
.queue-detail-title-row button { display: grid; width: 34px; height: 34px; margin-top: -2px; padding: 0; flex: none; place-items: center; border: 0; border-radius: 50%; color: var(--queue-blue); background: transparent; cursor: pointer; }
.queue-detail-title-row button:hover { background: var(--queue-position); }
.queue-detail-title-row button:disabled { color: var(--queue-tertiary); cursor: default; }
.queue-detail-action-choices { display: grid; margin-top: 9px; grid-template-columns: repeat(auto-fit, minmax(100px, 1fr)); gap: 7px; }
.queue-detail-action-choices.is-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.queue-detail-action-choices button { min-height: 40px; padding: 7px 9px; border: 1px solid var(--queue-separator); border-radius: 8px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; font-size: 11px; }
.queue-detail-action-choices button.active { border-color: var(--queue-blue); color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-detail-action-choices button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-detail-transfer-rows { display: grid; margin-top: 9px; gap: 7px; }
.queue-detail-transfer-rows button { display: flex; min-height: 52px; padding: 9px 11px; align-items: center; justify-content: space-between; gap: 12px; border: 1px solid var(--queue-separator); border-radius: 8px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; text-align: left; font: inherit; }
.queue-detail-transfer-rows button.active { border-color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-detail-transfer-rows button.is-unavailable { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-detail-transfer-rows button:disabled { cursor: default; }
.queue-detail-transfer-rows strong, .queue-detail-transfer-rows small { display: block; }
.queue-detail-transfer-rows strong { font-size: 12px; font-weight: 620; line-height: 1.4; }
.queue-detail-transfer-rows small { color: var(--queue-secondary); font-size: 10px; font-weight: 400; line-height: 1.5; }
.queue-detail-transfer-rows button.active .queue-detail-transfer-row-main strong { color: var(--queue-blue); }
.queue-detail-transfer-rows button.is-unavailable small { color: var(--queue-tertiary); }
.queue-detail-transfer-row-main { display: grid; min-width: 0; gap: 2px; }
.queue-detail-transfer-row-wait { display: grid; flex: none; gap: 2px; text-align: right; }
.queue-detail-transfer-row-wait small { color: var(--queue-tertiary); }
.queue-detail-action-confirm { display: grid; margin-top: 10px; grid-template-columns: 1fr 1.35fr; gap: 7px; }
.queue-detail-action-confirm button.primary { border-color: var(--queue-blue); color: #fff; background: var(--queue-blue); }
.queue-detail-action-confirm button.primary.is-danger:not(:disabled) { border-color: var(--queue-red) !important; color: #fff !important; background: var(--queue-red) !important; }
.queue-detail-action-error, .queue-detail-action-notice { margin: 9px 0 0; padding: 8px 9px; border-radius: 7px; font-size: 10px; line-height: 1.5; }
.queue-detail-action-error { color: var(--queue-red); background: var(--queue-soft-red); }
.queue-detail-action-notice { color: var(--queue-online); background: var(--queue-soft-online); }
.queue-detail-primary, .queue-detail-secondary { display: flex; width: 100%; min-height: 44px; margin-top: 17px; padding: 0 14px; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: 9px; cursor: pointer; font-size: 12px; font-weight: 600; transition: filter .16s ease, transform .12s ease; }
.queue-detail-primary:active, .queue-detail-secondary:active { transform: scale(.99); }
.queue-detail-primary { color: #fff; background: var(--queue-blue); }
.queue-detail-secondary { color: var(--queue-text); background: var(--queue-position); }
.queue-detail-privacy { max-width: 34em; margin: 8px auto 0; color: var(--queue-tertiary); font-size: 10px; line-height: 1.5; text-align: center; text-wrap: balance; }

.queue-version-dialog { width: min(100%, 460px); }
.queue-system-details { margin-top: 17px; }
.queue-system-details h3, .queue-version-section-title { margin: 0; border: 0; color: var(--queue-secondary); font-size: 10px; font-weight: 650; line-height: 1.4; }
.queue-system-details dl { margin: 8px 0 0; border-top: 1px solid var(--queue-separator); }
.queue-system-details dl > div { display: grid; min-height: 42px; padding: 8px 0; grid-template-columns: minmax(80px, auto) minmax(0, 1fr); align-items: center; gap: 16px; border-bottom: 1px solid var(--queue-separator); }
.queue-system-details dt, .queue-system-details dd { margin: 0; font-size: 10px; line-height: 1.5; }
.queue-system-details dt { color: var(--queue-tertiary); }
.queue-system-details dd { overflow-wrap: anywhere; text-align: right; }
.queue-version-section-title { margin-top: 18px; }
.queue-version-loading { display: flex; min-height: 170px; align-items: center; justify-content: center; flex-direction: column; gap: 10px; color: var(--queue-secondary); font-size: 11px; }
.queue-version-error { margin: 16px 0 0; padding: 10px 11px; border-left: 3px solid var(--queue-orange); color: var(--queue-secondary); background: var(--queue-soft-orange); font-size: 10px; line-height: 1.55; }
.queue-version-list { margin-top: 8px; border-top: 1px solid var(--queue-separator); }
.queue-version-list > section { padding: 13px 0; border-bottom: 1px solid var(--queue-separator); }
.queue-version-list > section > header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; }
.queue-version-list > section > header strong { min-width: 0; overflow: hidden; font-size: 13px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.queue-version-list > section > header span { padding: 4px 7px; flex: 0 0 auto; border-radius: 6px; color: var(--queue-secondary); background: var(--queue-position); font-size: 9px; font-weight: 620; }
.queue-version-list > section > header span.is-latest { color: #248a3d; background: color-mix(in srgb, #34c759 12%, var(--queue-card)); }
.queue-version-list > section > header span.is-update-available { color: var(--queue-orange); background: var(--queue-soft-orange); }
.queue-version-list > section > header span.is-ahead { color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-version-list dl { display: grid; margin: 9px 0 0; gap: 5px; }
.queue-version-list dl > div { display: grid; grid-template-columns: minmax(74px, auto) minmax(0, 1fr); gap: 14px; }
.queue-version-list dt, .queue-version-list dd { margin: 0; font-size: 10px; line-height: 1.45; }
.queue-version-list dt { color: var(--queue-tertiary); }
.queue-version-list dd { overflow-wrap: anywhere; text-align: right; font-variant-numeric: tabular-nums; }

.queue-online-dialog { width: min(100%, 520px); }
.queue-online-form { display: grid; margin-top: 18px; gap: 16px; }
.queue-online-field { display: grid; gap: 7px; }
.queue-online-field > span, .queue-online-options legend { color: var(--queue-secondary); font-size: 11px; font-weight: 580; line-height: 1.4; }
.queue-online-field input { width: 100%; height: 46px; padding: 0 13px; border: 1px solid var(--queue-separator); border-radius: 9px; outline: 0; color: var(--queue-text); background: var(--queue-position); font: inherit; font-size: 13px; letter-spacing: 0; transition: border-color .16s ease, box-shadow .16s ease, background .16s ease; }
.queue-online-field input:focus { border-color: var(--queue-blue); background: var(--queue-card); box-shadow: 0 0 0 3px color-mix(in srgb, var(--queue-blue) 14%, transparent); }
.queue-online-field input::placeholder { color: var(--queue-tertiary); }
.queue-online-options { min-width: 0; margin: 0; padding: 0; border: 0; }
.queue-online-options legend { margin-bottom: 7px; padding: 0; }
.queue-online-machine-groups { display: grid; gap: 11px; }
.queue-online-machine-groups section { min-width: 0; }
.queue-online-machine-groups h3 { margin: 0 0 6px; border: 0; color: var(--queue-tertiary); font-size: 9px; font-weight: 620; line-height: 1.4; }
.queue-online-machine-options { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(130px, 100%), 1fr)); gap: 8px; }
.queue-online-preference-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.queue-online-preference-options.is-stacked { grid-template-columns: minmax(0, 1fr); }
.queue-online-preference-option { min-width: 0; border: 1px solid var(--queue-separator); border-radius: 9px; background: var(--queue-position); }
.queue-online-preference-option.active { border-color: color-mix(in srgb, var(--queue-blue) 62%, var(--queue-separator)); background: var(--queue-soft-blue); }
.queue-online-preference-options .queue-online-preference-option > button { width: 100%; border: 0; background: transparent; }
.queue-online-preference-option > .solo-pass-tick-box { margin: 0 11px 10px; }
.queue-online-solo-default { display: grid; gap: 8px; }
.queue-online-solo-default > p { margin: 0; color: var(--queue-secondary); font-size: 12px; line-height: 1.5; }
.queue-online-solo-default > .solo-pass-tick-box { padding: 10px 11px; border: 1px solid var(--queue-separator); border-top: 1px solid var(--queue-separator); border-radius: 9px; background: var(--queue-position); }
.queue-online-machine-options button, .queue-online-preference-options button { display: flex; min-width: 0; min-height: 62px; padding: 10px 11px; justify-content: center; flex-direction: column; border: 1px solid var(--queue-separator); border-radius: 9px; color: var(--queue-text); background: var(--queue-position); text-align: left; cursor: pointer; transition: border-color .16s ease, background .16s ease, transform .12s ease; }
.queue-online-machine-options button:active:not(:disabled), .queue-online-preference-options button:active { transform: scale(.99); }
.queue-online-machine-options button.active, .queue-online-preference-options button.active { border-color: color-mix(in srgb, var(--queue-blue) 62%, var(--queue-separator)); background: var(--queue-soft-blue); }
.queue-online-machine-options button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-online-machine-options strong, .queue-online-machine-options span, .queue-online-machine-options small, .queue-online-preference-options strong, .queue-online-preference-options span { display: block; }
.queue-online-machine-options strong, .queue-online-preference-options strong { font-size: 12px; font-weight: 610; line-height: 1.4; }
.queue-online-machine-options span, .queue-online-preference-options span { margin-top: 4px; color: var(--queue-secondary); font-size: 9px; line-height: 1.45; }
.queue-online-machine-options small { margin-top: 5px; color: var(--queue-online); font-size: 9px; font-weight: 610; line-height: 1.35; }
.queue-online-machine-options button:disabled span { color: var(--queue-tertiary); }
.queue-online-machine-options button:disabled small { color: var(--queue-tertiary); }
/* One machine per row, as the terminal lists them: what waits there on the left, the wait of a new registration or a grey badge on the right. */
.queue-online-machine-options.is-rows { grid-template-columns: minmax(0, 1fr); }
.queue-online-machine-options.is-rows button { align-items: center; justify-content: space-between; flex-direction: row; gap: 12px; }
.queue-online-machine-options.is-rows .queue-online-machine-main { min-width: 0; margin-top: 0; }
.queue-online-machine-options.is-rows .queue-online-machine-wait { margin-top: 0; flex: none; text-align: right; }
.queue-online-machine-options.is-rows .queue-online-machine-wait small { margin-top: 0; color: var(--queue-tertiary); font-weight: 400; }
.queue-online-machine-options.is-rows .queue-online-machine-wait strong { margin-top: 2px; }
.queue-online-machine-options.is-rows .queue-online-machine-badge { margin-top: 0; padding: 3px 8px; flex: none; border-radius: 100px; color: var(--queue-secondary); background: color-mix(in srgb, var(--queue-tertiary) 18%, transparent); font-weight: 570; }
.queue-online-recommended { display: inline-block; margin-left: 6px; padding: 1px 7px; border-radius: 100px; color: var(--queue-blue); background: color-mix(in srgb, var(--queue-blue) 13%, transparent); font-size: 9px; font-style: normal; font-weight: 610; line-height: 1.5; vertical-align: 1px; }
.queue-online-wait-summary { display: grid; padding: 11px 12px; gap: 2px; border: 1px solid var(--queue-separator); border-radius: 9px; background: var(--queue-position); }
.queue-online-wait-summary span { color: var(--queue-secondary); font-size: 10px; line-height: 1.45; }
.queue-online-wait-summary strong { font-size: 15px; font-weight: 640; line-height: 1.4; }
.queue-online-wait-summary small { color: var(--queue-tertiary); font-size: 10px; line-height: 1.5; }
.queue-online-profile { margin: 0; border-top: 1px solid var(--queue-separator); }
.queue-online-profile > div { display: grid; min-height: 42px; padding: 8px 0; grid-template-columns: minmax(92px, auto) minmax(0, 1fr); align-items: center; gap: 14px; border-bottom: 1px solid var(--queue-separator); }
.queue-online-profile dt, .queue-online-profile dd { margin: 0; font-size: 11px; line-height: 1.5; }
.queue-online-profile dt { color: var(--queue-tertiary); }
.queue-online-profile dd { overflow-wrap: anywhere; text-align: right; }
.queue-online-gender { display: flex; align-items: center; justify-content: flex-end; gap: 5px; }
.queue-online-gender > span { color: var(--queue-tertiary); font-size: 15px; font-weight: 650; }
.queue-online-gender.is-male > span { color: #1677d2; }
.queue-online-gender.is-female > span { color: #d7558b; }
.queue-online-check-in-notice { display: flex; padding: 11px 12px; align-items: flex-start; gap: 9px; border-left: 3px solid var(--queue-online); color: var(--queue-online); background: var(--queue-soft-online); }
.queue-online-check-in-notice > svg { margin-top: 1px; flex: 0 0 auto; }
.queue-online-check-in-notice p { margin: 0; }
.queue-online-check-in-notice strong, .queue-online-check-in-notice span { display: block; }
.queue-online-check-in-notice strong { font-size: 11px; font-weight: 640; line-height: 1.45; }
.queue-online-check-in-notice span { margin-top: 2px; color: var(--queue-secondary); font-size: 10px; line-height: 1.55; }
.queue-online-other-notice { display: flex; padding: 11px 12px; align-items: flex-start; gap: 9px; border-left: 3px solid var(--queue-orange); color: var(--queue-orange); background: var(--queue-soft-orange); }
.queue-online-other-notice > svg { margin-top: 1px; flex: 0 0 auto; }
.queue-online-other-notice p { margin: 0; }
.queue-online-other-notice strong, .queue-online-other-notice span { display: block; }
.queue-online-other-notice strong { font-size: 11px; font-weight: 640; line-height: 1.45; }
.queue-online-other-notice span { margin-top: 2px; color: var(--queue-secondary); font-size: 10px; line-height: 1.55; }
.queue-online-capacity-notice { display: flex; padding: 11px 12px; align-items: flex-start; gap: 9px; border-left: 3px solid var(--queue-blue); color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-online-capacity-notice > svg { margin-top: 1px; flex: 0 0 auto; }
.queue-online-capacity-notice p { margin: 0; }
.queue-online-capacity-notice strong, .queue-online-capacity-notice span { display: block; }
.queue-online-capacity-notice strong { font-size: 11px; font-weight: 640; line-height: 1.45; }
.queue-online-capacity-notice span { margin-top: 2px; color: var(--queue-secondary); font-size: 10px; line-height: 1.55; }
.queue-machine-metadata { margin-top: 16px; }
.queue-online-error { margin: -4px 0 0; padding: 9px 10px; border-radius: 7px; color: var(--queue-red); background: var(--queue-soft-red); font-size: 10px; line-height: 1.5; }
.queue-online-primary, .queue-online-secondary { display: flex; width: 100%; min-height: 44px; padding: 0 14px; align-items: center; justify-content: center; border: 0; border-radius: 9px; cursor: pointer; font-size: 12px; font-weight: 600; }
.queue-online-primary { color: #fff; background: var(--queue-blue); }
.queue-online-secondary { color: var(--queue-text); background: var(--queue-position); }
.queue-online-primary:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-online-hint { max-width: 38em; margin: -8px auto 0; color: var(--queue-tertiary); font-size: 9px; line-height: 1.5; text-align: center; }
.queue-online-actions { display: grid; grid-template-columns: 1fr 1.35fr; gap: 8px; }
.queue-online-actions button { min-height: 44px; border: 0; border-radius: 9px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; font-size: 11px; font-weight: 590; }
.queue-online-actions button.primary { color: #fff; background: var(--queue-blue); }
.queue-online-actions button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-online-other-button { margin-top: 0; border: 1px solid var(--queue-separator); }
.queue-online-result { display: flex; min-height: 250px; padding: 24px 4px 4px; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.queue-online-result-icon { display: grid; width: 48px; height: 48px; place-items: center; border-radius: 50%; color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-online-result-icon.is-information { color: var(--queue-online); background: var(--queue-soft-online); }
.queue-online-result.is-success .queue-online-result-icon { color: #248a3d; background: color-mix(in srgb, #34c759 12%, var(--queue-card)); }
.queue-online-result.is-rejected .queue-online-result-icon { color: var(--queue-red); background: var(--queue-soft-red); }
.queue-online-result > strong { margin-top: 14px; font-size: 16px; font-weight: 640; line-height: 1.4; }
.queue-online-result > p { max-width: 38em; margin: 6px 0 0; color: var(--queue-secondary); font-size: 11px; line-height: 1.65; }
.queue-online-result > .queue-online-check-in-notice { width: 100%; margin-top: 16px; text-align: left; }
.queue-online-result > .queue-online-primary, .queue-online-result > .queue-online-secondary { margin-top: 18px; }
.queue-confirm-dialog { width: min(100%, 420px); }
.queue-confirm-dialog p { margin: 8px 0 0; color: var(--queue-secondary); font-size: 12px; line-height: 1.65; }
.queue-confirm-dialog > div { display: grid; margin-top: 18px; grid-template-columns: 1fr 1fr; gap: 8px; }
.queue-confirm-dialog button { min-height: 42px; border: 0; border-radius: 9px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; font-size: 11px; font-weight: 580; }
.queue-confirm-dialog button.primary { color: #fff; background: var(--queue-blue); }
.queue-dialog-enter-active, .queue-dialog-leave-active { transition: opacity .18s ease; }
.queue-dialog-enter-active .queue-detail-dialog, .queue-dialog-enter-active .queue-confirm-dialog, .queue-dialog-leave-active .queue-detail-dialog, .queue-dialog-leave-active .queue-confirm-dialog { transition: transform .2s ease, opacity .18s ease; }
.queue-dialog-enter-from, .queue-dialog-leave-to { opacity: 0; }
.queue-dialog-enter-from .queue-detail-dialog, .queue-dialog-enter-from .queue-confirm-dialog, .queue-dialog-leave-to .queue-detail-dialog, .queue-dialog-leave-to .queue-confirm-dialog { opacity: 0; transform: translateY(8px) scale(.985); }
.queue-footer { min-height: 38px; padding: 14px 4px 0; color: var(--queue-tertiary); font-size: 11px; text-align: center; }
.queue-footer.is-error { color: var(--queue-orange); }

@media (min-width: 620px) {
  .queue-header { align-items: flex-end; flex-direction: row; justify-content: space-between; }
  .queue-toolbar { width: auto; align-items: center; flex-direction: row; }
  .queue-view-tabs { width: 190px; }
  .queue-system { width: auto; grid-template-columns: auto minmax(92px, auto) auto; }
  .queue-position-list { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; }
  .queue-machine-list.is-single .queue-position-list {
    grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr));
  }
  .queue-machine-list.is-single .queue-position-list:has(> .queue-position:nth-child(2):last-child) {
    grid-template-columns: minmax(0, 1fr);
  }
  .queue-position.is-playing { grid-column: 1 / -1; }
  .queue-position.is-playing .queue-registration-grid:has(> .queue-overtime):has(> .queue-registration + .queue-registration) {
    grid-template-columns: minmax(0, 1.25fr) repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 820px) {
  .queue-panel { padding-top: 12px; }
  .queue-heading h1 { font-size: 38px; }
}

@media (min-width: 1100px) {
  .queue-machine-list { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; }
  .queue-machine-list.is-single { grid-template-columns: minmax(0, 1fr); }
}

@media (max-width: 619px) {
  .queue-position.is-playing .queue-registration-grid:has(> .queue-overtime) > .queue-overtime {
    grid-column: 1 / -1;
  }
  .queue-position.is-playing .queue-overtime + .queue-registration:last-child {
    grid-column: 1 / -1;
  }
  .queue-self { grid-template-columns: 38px minmax(0, 1fr); padding: 15px; }
  .queue-self-icon { width: 38px; height: 38px; }
  .queue-self-clear { grid-column: 2; justify-self: start; padding: 4px 0; }
  .queue-logs { padding: 15px; }
  .queue-logs-header { align-items: stretch; flex-direction: column; }
  .queue-log-filters button { padding: 0 3px; font-size: 9px; }
  .queue-log-list li { grid-template-columns: 1fr; gap: 7px; }
  .queue-log-time { display: flex; align-items: center; gap: 7px; }
  .queue-log-time span { margin: 0; }
  .queue-detail-backdrop { padding: 10px; align-items: end; }
  .queue-detail-dialog, .queue-confirm-dialog { width: 100%; max-height: calc(100vh - 20px); padding: 18px; border-radius: 14px; }
}

@media (prefers-reduced-motion: reduce) {
  .spinning { animation: none; }
  .queue-dialog-enter-active, .queue-dialog-leave-active,
  .queue-dialog-enter-active .queue-detail-dialog, .queue-dialog-enter-active .queue-confirm-dialog,
  .queue-dialog-leave-active .queue-detail-dialog, .queue-dialog-leave-active .queue-confirm-dialog { transition: none; }
}

@keyframes queue-spin { to { transform: rotate(360deg); } }
</style>
