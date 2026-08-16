<script setup>
import {
  ChevronRight,
  CircleCheck,
  History,
  Info,
  MapPin,
  RefreshCw,
  TriangleAlert,
  UserPlus,
  UserRound,
  UserRoundCheck,
  Users,
  WifiOff,
  X
} from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import MobileRegistrationFlow from './MobileRegistrationFlow.vue'
import PlayerAccountDialog from './PlayerAccountDialog.vue'
import { compactMiddleDots, formatMiddleDots, normalizeMachineConfiguration } from './machineConfiguration.js'

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
    ? '左侧 · 机台 A'
    : index === 1
      ? '右侧 · 机台 B'
      : index === 2
        ? '中间左侧 · 机台 C'
        : index === 3
           ? '中间右侧 · 机台 D'
           : `第 ${index + 1} 台 · 机台 ${id}`
}))
const logSourceDefinitions = [
  { value: 'ALL', label: '全部来源' },
  { value: 'ON_SITE_TERMINAL', label: '现场终端' },
  { value: 'QQ_BOT', label: 'QQ Bot' },
  { value: 'SYSTEM_AUTOMATIC', label: '系统自动' },
  { value: 'WEBSITE_REMOTE', label: '网站远程' },
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
const detailActionSubmitting = ref(false)
const detailActionError = ref('')
const detailActionNotice = ref('')
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
const onlineJoinLoading = ref(false)
const onlineJoinError = ref('')
const onlineJoinCommandId = ref(null)
const onlineJoinQueueId = ref(null)
const onlineJoinMachineConfigurationRevision = ref(null)
const onlineJoinResultDetail = ref('')
const onlineJoinResultRegistrationId = ref(null)
const onlineJoinTerminalApplied = ref(false)
const mobileRegistrationToken = ref('')
const playerAccountBindingToken = ref('')
const playerAccountDialogVisible = ref(false)
const playerAccountFocusRegistrationId = ref('')
let refreshTimer
let clockTimer
let onlineCommandTimer
let detailActionTimer

const totalRegistrationCount = computed(() => (
  machines.value.reduce((total, machine) => total + machine.registrationCount, 0)
))

const machineCountSummary = computed(() => (
  machines.value.length === 1
    ? '当前使用 1 台机台'
    : `${machines.value.length} 台机台的登记顺序彼此独立`
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
    { key: 'website', name: '队列网站' },
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
    estimatedWaitMinutes: null,
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

const onlineJoinCanSubmit = computed(() => (
  selectedOnlineJoinMachine.value?.available === true &&
  (!onlineJoinNeedsPreference.value || ['SOLO', 'OPEN_TO_JOIN'].includes(onlineJoinPreference.value))
))

const availability = computed(() => {
  if (!hasSnapshot.value) {
    return { label: loading.value ? '正在连接排队终端' : '排队终端暂未接入', tone: 'offline' }
  }
  if (snapshotStale.value) return { label: '队列数据已过期', tone: 'offline' }
  if (loadError.value) return { label: '连接暂时中断', tone: 'closed' }
  if (!terminalOnline.value) return { label: '排队终端离线', tone: 'offline' }
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
        registrations: machine.playing
      })
    })
    machine.waitingPositions.forEach((position, index) => {
      position.registrations.forEach((registration) => {
        locations.push({
          registration,
          machine,
          kind: 'WAITING',
          label: `位置 ${machine.id}${index + 1}`,
          estimate: position.estimatedWaitMinutes,
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
  return '当前为单人安排'
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
      detail: '现场已开始新的排队批次，这份线上登记不再有效。',
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
  { value: 'SYSTEM', label: '系统' }
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
    ))
  })).filter((group) => group.machines.length > 0)
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
    registrationCount: 0
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
      temporarilyAway: false,
      temporaryAwaySkippedTurns: 0,
      fixedPair: false,
      fixedPairId: null,
      noShowCount: 0,
      lastNoShowActionWasDefer: false,
      onlineRegistrationPendingCheckIn: false,
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
    registrationType: String(
      source?.registration_type || source?.registrationType || 'TEMPORARY'
    ).toUpperCase(),
    qqNumber: normalizeQqNumber(source?.qq_number ?? source?.qqNumber),
    createdAt: source?.created_at ?? source?.createdAt ?? null,
    onlineCheckInStartedAt:
      source?.online_check_in_started_at ?? source?.onlineCheckInStartedAt ?? null,
    lastPlayedAt: source?.last_played_at ?? source?.lastPlayedAt ?? null
  }
}

function normalizePosition(source, index) {
  const registrationsSource = Array.isArray(source) ? source : source?.registrations
  const registrations = Array.isArray(registrationsSource)
    ? registrationsSource.map(normalizeRegistration)
    : []
  const fixedPair = source?.fixed_pair === true || source?.fixedPair === true ||
    (registrations.length === 2 && registrations.every((registration) => registration.fixedPair))

  return {
    registrations: fixedPair
      ? registrations.map((registration) => ({ ...registration, fixedPair: true }))
      : registrations,
    fixedPair,
    estimatedWaitMinutes: toNonNegativeInteger(
      source?.estimated_wait_minutes ?? source?.estimatedWaitMinutes
    ),
    capacity: Number(source?.configuration?.capacity ?? source?.capacity) === 1 ? 1 : 2,
    positionId: source?.position_id ?? source?.positionId ?? null,
    commonPlayPreview: normalizeCommonPlayPreview(
      source?.common_play_preview ?? source?.commonPlayPreview
    ),
    index
  }
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
    )
  }
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
  const normalizedMachines = layout.definitions.map((definition, index) => (
    normalizeMachine(definition, definitionsWithSources[index].source)
  ))
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
  return normalizedReason === 'OTHER' && normalizedDetail
    ? `${label}（${normalizedDetail}）`
    : label
}

function machineSummary(machine) {
  if (!machine.synced) return '尚未同步现场状态'
  const queueSummary = `${machine.waitingPositions.length} 个等待位置·${machine.registrationCount} 个登记`
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

function absenceLabel(registration) {
  if (registration.temporarilyAway) {
    return registration.temporaryAwaySkippedTurns > 0
      ? `暂时离开 · 已轮空 ${registration.temporaryAwaySkippedTurns} 次`
      : '暂时离开'
  }
  if (registration.deferredOnce) return '暂缓一次'
  return null
}

function registrationLabel(registration) {
  return (registration.onlineRegistrationPendingCheckIn ? '线上登记 · 待签到' : null) ||
    absenceLabel(registration) ||
    (registration.noShowCount > 0 ? `未到场 ${registration.noShowCount} 次` : null) ||
    (registration.fixedPair ? '固定组合' : null) ||
    (registration.preference === 'SOLO' ? '单人游玩' : '允许他人加入')
}

function registrationTone(registration) {
  if (registration.onlineRegistrationPendingCheckIn) return 'online'
  if (registration.temporarilyAway || registration.deferredOnce) return 'absence'
  if (registration.noShowCount > 0) return 'warning'
  return 'normal'
}

function positionEstimateText(minutes, registrations = [], machine = null) {
  if (snapshotStale.value) return '数据已过期，暂时无法估算'
  if (!terminalOnline.value) return '终端恢复同步后重新估算'
  if (machine?.operational === false) return '机台恢复使用后重新估算'
  if (registrations.some((registration) => registration.temporarilyAway)) {
    return '暂时离开，无法估算'
  }
  if (minutes === null || minutes === undefined) return '暂时无法估算'
  if (minutes <= 0) return '预计很快可以游玩'
  return `约 ${minutes} 分钟后可以游玩`
}

function registrationTypeLabel(registration) {
  return registration.registrationType === 'PLAYER_PROFILE' ? '玩家资料登记' : '临时登记'
}

function preferenceLabel(registration) {
  if (registration.fixedPair) return '与朋友共同游玩'
  return registration.preference === 'SOLO' ? '单人游玩' : '允许他人加入'
}

function registrationPartnerText(detail) {
  if (!detail || detail.kind !== 'registration') return null
  const partners = detail.locationRegistrations.filter((registration) => (
    registration.registrationId !== detail.registration.registrationId
  ))
  if (partners.length) {
    const action = detail.isPlaying ? '正在与' : '将与'
    return `${action}${partners.map((partner) => `“${partner.displayId}”`).join('、')}共同游玩`
  }
  if (detail.commonPlayPreview) {
    return `预计与“${detail.commonPlayPreview.displayId}”共同游玩`
  }
  return null
}

function noShowResultLabel(registration) {
  if (!registration.noShowCount) return null
  return registration.lastNoShowActionWasDefer
    ? `未到场 ${registration.noShowCount} 次 · 上次处理：暂缓一次`
    : `未到场 ${registration.noShowCount} 次 · 上次处理：移至队尾`
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

function resetDetailAction() {
  if (detailActionTimer) window.clearTimeout(detailActionTimer)
  detailActionTimer = null
  detailActionMode.value = null
  detailActionTargetMachineId.value = ''
  detailActionPreference.value = 'SOLO'
  detailActionSubmitting.value = false
  detailActionError.value = ''
  detailActionNotice.value = ''
}

function accountQueueRegistrationFor(registration) {
  if (!registration?.registrationId) return null
  return playerAccountQueueState.value?.registrations?.find((candidate) => (
    candidate.registration_id === registration.registrationId
  )) || null
}

function detailRegistrationAbsenceStatus(registration) {
  if (registration?.deferred_once) return 'DEFER_ONE_ROUND'
  if (registration?.temporarily_away) return 'TEMPORARILY_AWAY'
  return 'NONE'
}

function detailActionPrompt(registration, mode) {
  if (!registration) return null
  const subject = registration.fixed_pair ? '固定组合的两份登记' : '这份登记'
  if (mode === 'defer') {
    return {
      title: registration.fixed_pair ? '确认整组暂缓一次？' : '确认暂缓一次？',
      detail: registration.position === 'PLAYING'
        ? `${subject}会离开游玩位置并回到等待顺序前端。这次游玩机会会被跳过，原有顺序保持不变，随后自动解除暂缓。`
        : `下一次轮到${subject}时不会进入游玩位置。${subject}会保持当前顺序，在跳过这次机会后自动解除暂缓。`,
      note: '暂缓的登记本轮不会占用共同游玩位置，系统会按照其余在场登记的游玩偏好重新组成下一轮。',
      confirm: '确认暂缓一次',
      operation: 'DEFER_ONE_ROUND'
    }
  }
  if (mode === 'temporary_leave') {
    return {
      title: registration.fixed_pair ? '确认整组暂时离开？' : '确认暂时离开？',
      detail: registration.position === 'PLAYING'
        ? `${subject}会离开游玩位置，并按一次轮空移至当前等待顺序末端。之后每次轮到时仍会移至队尾，状态不会自动解除。`
        : `下一次轮到${subject}时不会进入游玩位置，而会按一次轮空移至当前等待顺序末端；之后每次轮到时仍会移至队尾。`,
      note: '暂时离开的登记不会占用共同游玩位置。玩家返回后需要手动取消暂时离开；连续轮空 3 次后仍未取消，第四次轮到时会自动退出排队。',
      confirm: '确认暂时离开',
      operation: 'TEMPORARILY_LEAVE'
    }
  }
  if (mode === 'cancel_defer') {
    return {
      title: registration.fixed_pair ? '确认整组取消暂缓一次？' : '确认取消暂缓一次？',
      detail: `${subject}会恢复下一次游玩机会，并保持当前登记顺序。`,
      confirm: '确认取消暂缓一次',
      operation: 'CANCEL_DEFER_ONE_ROUND'
    }
  }
  if (mode === 'cancel_temporary_leave') {
    return {
      title: registration.fixed_pair ? '确认整组取消暂时离开？' : '确认取消暂时离开？',
      detail: `${subject}会恢复正常轮候，并将已轮空次数清零。`,
      confirm: '确认取消暂时离开',
      operation: 'CANCEL_TEMPORARY_LEAVE'
    }
  }
  if (mode === 'leave') {
    const details = [`${subject}会退出当前队列，继续游玩时需要重新加入排队。`]
    if (registration.fixed_pair) {
      details.push('固定组合会解除；另一份登记保留原位，并恢复为允许他人加入。')
    }
    if (registration.position === 'PLAYING') {
      details.push('游玩位置中的空缺不会自动由等待顺序中的下一组登记补入。')
    }
    return {
      title: '确认退出排队？',
      detail: details.join(' '),
      confirm: '确认退出排队',
      operation: 'LEAVE_QUEUE',
      danger: true
    }
  }
  return null
}

function detailTransferPrompt(registration) {
  const target = playerAccountQueueState.value?.queue?.machines?.find((machine) => (
    machine.id === detailActionTargetMachineId.value
  ))
  if (!target) return null
  const details = [
    `这会将“${registration.display_id || registration.displayId}”从${registration.machine_name}移出，并加入${target.name}的登记顺序末端。`,
    '原机台上的当前位置和排队顺序不会保留；之后即使转回，也只能加入转回时的队尾。'
  ]
  if (target.capacity === 1) {
    details.push(`${target.name}仅能容纳一人游玩，转入后本次登记会使用“单人游玩”；玩家资料中的默认游玩偏好不会改变。`)
  }
  if (registration.fixed_pair) {
    details.push('当前登记属于固定组合，只转移本人会解除固定组合；另一份登记保留原位并恢复为允许他人加入。')
  }
  if (registration.deferred_once) details.push('转入后这份登记不再暂缓一次。')
  if (registration.temporarily_away) details.push('暂时离开状态和已轮空次数会保留，返回后仍需手动取消。')
  return {
    title: `转至 ${target.name}？`,
    detail: details.join(' '),
    confirm: `确认转至 ${target.name}`
  }
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

function detailQueueOperationPayload(registration, operation, extra = {}) {
  const state = playerAccountQueueState.value
  return {
    request_id: createRequestId(),
    operation,
    expected_queue_id: state.queue.queue_id,
    expected_registration_id: registration.registration_id,
    expected_machine_id: registration.machine_id,
    expected_position: registration.position,
    expected_fixed_pair_id: registration.fixed_pair_id || null,
    expected_absence_status: detailRegistrationAbsenceStatus(registration),
    expected_temporary_away_skipped_turns: registration.temporary_away_skipped_turns || 0,
    expected_pending_check_in: registration.online_registration_pending_check_in === true,
    expected_machine_configuration_revision: state.queue.machine_configuration_revision,
    expected_machine_stable_id: registration.machine_stable_id,
    ...extra
  }
}

function finishDetailQueueAction(message, errorMessage = '') {
  detailActionSubmitting.value = false
  detailActionMode.value = null
  detailActionNotice.value = message
  detailActionError.value = errorMessage
  refreshLoggedInPlayerQueue()
  loadQueue(true)
}

async function pollDetailQueueAction(commandId, attempts = 0) {
  try {
    const response = await fetch(`${QUEUE_ONLINE_COMMAND_API_BASE}/${encodeURIComponent(commandId)}`, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.error || '暂时无法读取现场处理结果。')
    if (payload.status === 'PENDING' && attempts < 30) {
      detailActionTimer = window.setTimeout(() => pollDetailQueueAction(commandId, attempts + 1), 1500)
      return
    }
    if (payload.status === 'APPLIED') {
      finishDetailQueueAction(payload.result_detail || '现场终端已完成这次操作。')
    } else if (payload.status === 'PENDING') {
      finishDetailQueueAction('操作仍在等待现场终端处理，请稍后刷新查看。')
    } else {
      finishDetailQueueAction('', payload.result_detail || '现场终端没有执行这次操作。')
    }
  } catch (error) {
    if (attempts < 30) {
      detailActionTimer = window.setTimeout(() => pollDetailQueueAction(commandId, attempts + 1), 1500)
    } else {
      finishDetailQueueAction('', error.message)
    }
  }
}

async function submitDetailQueueAction(accountRegistration, operation, extra = {}) {
  const state = playerAccountQueueState.value
  if (!accountRegistration || !state?.queue || detailActionSubmitting.value) return
  if (!state.queue.remote_actions) {
    detailActionError.value = '现场未开启网站远程操作，当前只能查看状态。'
    return
  }
  detailActionSubmitting.value = true
  detailActionError.value = ''
  detailActionNotice.value = '操作已提交，正在等待现场终端确认。'
  try {
    const response = await fetch(`${PLAYER_ACCOUNT_API_URL}/queue-commands`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...detailAccountCsrfHeaders() },
      body: JSON.stringify(detailQueueOperationPayload(accountRegistration, operation, extra))
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.error || '暂时无法提交这次操作。')
    if (payload.status === 'APPLIED' || payload.status === 'REJECTED') {
      await pollDetailQueueAction(payload.command_id)
    } else {
      pollDetailQueueAction(payload.command_id)
    }
  } catch (error) {
    finishDetailQueueAction('', error.message)
  }
}

function openDetailAction(mode) {
  detailActionMode.value = mode
  detailActionError.value = ''
  detailActionNotice.value = ''
}

function openRegistration(
  machine,
  registration,
  locationLabel,
  estimatedWaitMinutes = null,
  locationRegistrations = [],
  commonPlayPreview = null,
  isPlaying = false,
  preserveAction = false
) {
  if (!preserveAction) resetDetailAction()
  selectedDetail.value = {
    kind: 'registration',
    title: registration.displayId,
    machine,
    locationLabel,
    estimatedWaitMinutes,
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
    if (accountSessionActive.value) return '当前队列中没有你的登记'
    if (identity?.queueId !== queueId.value) return '当前队列中还没有你的登记'
    if (markedSelfLastEvent.value) return eventOutcomeTitle(markedSelfLastEvent.value)
    return '你的登记目前不在队列中'
  }
  const registration = location.registration
  if (!location.machine.operational) return `${location.machine.name} 已停止使用`
  if (registration.onlineRegistrationPendingCheckIn) return '线上登记仍待现场签到'
  if (registration.temporarilyAway) return '你当前处于暂时离开状态'
  if (registration.deferredOnce) return '你已暂缓一次'
  if (location.kind === 'PLAYING') return '现在轮到你游玩'
  return positionEstimateText(location.estimate, location.registrations, location.machine)
}

function markedSelfStatusDetail() {
  const location = markedSelfLocation.value
  if (!terminalOnline.value) {
    return location
      ? `最后一次同步时，你位于${location.label}。终端恢复同步后，请再确认当前安排。`
      : `${snapshotStale.value ? '队列长时间没有更新' : '现场终端暂时离线'}，暂时无法确认${accountSessionActive.value ? '登录玩家' : '你的'}当前状态。${accountSessionActive.value ? '恢复同步后会按登录玩家资料重新匹配。' : '标记会继续保留。'}`
  }
  if (!location) {
    if (markedSelfAmbiguous.value) {
      return accountSessionActive.value
        ? '当前账户关联到多份登记，网页不会自动选择其中一份。请打开玩家资料查看，或在现场终端处理。'
        : `当前有多份昵称为“${markedSelf.value?.displayId}”的登记。请点开属于你的登记，并再次选择“标记为自己”。`
    }
    if (accountSessionActive.value) return '当前账户在这个队列中没有登记。'
    if (markedSelf.value?.queueId !== queueId.value) {
      return '标记会继续保留；使用相同昵称加入当前队列后，位置和预计时间会自动恢复更新。'
    }
    const event = markedSelfLastEvent.value
    if (event) {
      return `${event.detail} 标记会继续保留，使用相同昵称重新加入后将自动恢复更新。`
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
    return hasRestartedOnlineCheckInWindow(registration)
      ? '机台恢复正常使用后，这份登记已重新获得 30 分钟签到时限。请在本次时限内到现场终端点击自己的登记并选择“已到场”；超过 30 分钟，或轮到进入游玩位置时仍未签到，这份登记会自动退出排队。'
      : '请在创建登记后的 30 分钟内，到现场终端点击自己的登记并选择“已到场”。超过 30 分钟，或轮到进入游玩位置时仍未签到，这份登记会自动退出排队。'
  }
  if (registration.temporarilyAway) {
    const skipped = registration.temporaryAwaySkippedTurns
    return skipped > 0
      ? `排队时会暂时忽略这份登记，目前已轮空 ${skipped} 次；第四次轮到时仍未解除，将退出排队。`
      : '排队时会暂时忽略这份登记。需要在终端手动解除，才能再次进入游玩位置。'
  }
  if (registration.deferredOnce) return '这份登记原本的本次游玩机会会被跳过，并在该机会处理完成后自动解除。'
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
    location.registration.deferredOnce) return 'warning'
  return location.kind === 'PLAYING' ? 'playing' : 'normal'
}

function eventOutcomeTitle(event) {
  const labels = {
    NO_SHOW_DEFERRED: '你被标记为未到场，并已暂缓一次',
    NO_SHOW_MOVED_TO_TAIL: '你被标记为未到场，并已移至队尾',
    NO_SHOW_REMOVED: '你被标记为未到场，登记已被移除',
    TEMPORARY_AWAY_EXPIRED: '暂时离开已达到轮空上限，登记已退出排队',
    ONLINE_REGISTRATION_ADDED: '线上登记已经创建，请按时到现场签到',
    ONLINE_CHECK_IN_COMPLETED: '你已完成现场签到',
    ONLINE_CHECK_IN_TIMED_OUT: '超过 30 分钟未签到，登记已退出排队',
    ONLINE_CHECK_IN_MISSED: '轮到进入游玩位置时未签到，登记已退出排队',
    REGISTRATION_REMOVED: '这份登记已离开队列',
    REGISTRATION_CLOSED: '登记排队已经关闭',
    QUEUE_RESET: '现场已经开始新的队列'
  }
  return labels[event.type] || event.title
}

function eventTypeLabel(type) {
  const labels = {
    REGISTRATION_ADDED: '新增登记',
    REGISTRATION_REMOVED: '移除登记',
    REGISTRATION_UPDATED: '登记变动',
    QUEUE_REORDERED: '顺序调整',
    PLAYING_CHANGED: '游玩位置',
     NO_SHOW_DEFERRED: '未到场 · 暂缓一次',
     NO_SHOW_MOVED_TO_TAIL: '未到场 · 移至队尾',
     NO_SHOW_REMOVED: '未到场 · 移除登记',
    TEMPORARY_AWAY_EXPIRED: '暂时离开期满退出',
    ONLINE_REGISTRATION_ADDED: '线上登记',
    ONLINE_CHECK_IN_COMPLETED: '现场签到',
    ONLINE_CHECK_IN_TIMED_OUT: '签到超时',
    ONLINE_CHECK_IN_MISSED: '轮到时未签到',
    ABSENCE_CHANGED: '暂缓一次或暂时离开',
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
    WEBSITE_REMOTE: '网站远程',
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
      (!operational ? '机台已停止使用' : registrationCount >= 20 ? '登记已满' : null)
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
    setupComplete: Number(source.setup_version ?? source.setupVersion ?? 0) >= 1
  }
}

async function readJsonResponse(response) {
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    const error = new Error(data?.error || `服务器返回 HTTP ${response.status}`)
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
  onlineJoinLoading.value = false
  onlineJoinError.value = ''
  onlineJoinCommandId.value = null
  onlineJoinQueueId.value = null
  onlineJoinMachineConfigurationRevision.value = null
  onlineJoinResultDetail.value = ''
  onlineJoinResultRegistrationId.value = null
  onlineJoinTerminalApplied.value = false
}

function invalidateOnlineJoinConfirmation(message) {
  if (!['CONFIRM', 'EXISTING'].includes(onlineJoinStep.value)) return
  onlineJoinStep.value = 'LOOKUP'
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
  if (!['PENDING', 'REJECTED'].includes(onlineJoinStep.value)) resetOnlineJoin()
  onlineJoinVisible.value = true
  if (playerAccount.value?.profile?.qq_number) {
    onlineJoinAudience.value = 'SELF'
    onlineJoinQq.value = playerAccount.value.profile.qq_number
    onlineJoinStep.value = 'SELF_LOADING'
    queryOnlineProfile()
  }
}

function closeOnlineJoin() {
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

function selectOnlineJoinMachine(machine) {
  if (!machine.available || onlineJoinLoading.value) return
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
    onlineJoinError.value = '请输入 5 至 12 位 QQ 号。'
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

function onlineMachineEstimateText(machine) {
  if (snapshotStale.value) return '队列数据已过期'
  if (!terminalOnline.value) return '现场终端离线'
  if (!machine?.available) return machine?.unavailableReason || '当前不可加入'
  const minutes = machine.estimatedWaitMinutes
  if (minutes === null || minutes === undefined) return '暂时无法估算'
  if (minutes <= 0) return '预计很快可以游玩'
  return `新登记约 ${minutes} 分钟后可以游玩`
}

function existingOnlineRegistrationText() {
  const existing = onlineJoinExistingRegistration.value
  if (!existing) return ''
  const machineId = existing.machine_id ?? existing.machineId
  const position = existing.position
  const positionIndex = existing.position_index ?? existing.positionIndex
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
    const response = await fetch(`${QUEUE_ONLINE_COMMAND_API_BASE}/${encodeURIComponent(commandId)}`, {
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    })
    const command = await readJsonResponse(response)
    if (commandId !== onlineJoinCommandId.value) return
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
  } catch {
    scheduleOnlineCommandPoll()
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
  const requestId = onlineJoinCommandId.value || createRequestId()
  onlineJoinCommandId.value = requestId
  onlineJoinTerminalApplied.value = false
  onlineJoinLoading.value = true
  onlineJoinError.value = ''
  try {
    const requestPayload = {
      request_id: requestId,
      qq: profile.qqNumber,
      machine_id: machine.id,
      preference,
      expected_queue_id: onlineJoinQueueId.value,
      expected_machine_configuration_revision: onlineJoinMachineConfigurationRevision.value,
      expected_machine_stable_id: machine.stableId
    }
    const sendRequest = (payload) => fetch(QUEUE_ONLINE_JOIN_API_URL, {
      method: 'POST',
      cache: 'no-store',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    let command
    try {
      command = await readJsonResponse(await sendRequest(requestPayload))
    } catch (error) {
      if (
        error?.status !== 400 ||
        error?.message !== '请求包含不支持的线上登记字段'
      ) throw error
      const { expected_machine_stable_id: _ignored, ...legacyPayload } = requestPayload
      command = await readJsonResponse(await sendRequest(legacyPayload))
    }
    onlineJoinCommandId.value = command.command_id || requestId
    onlineJoinStep.value = 'PENDING'
    if (command.status === 'APPLIED' || command.status === 'REJECTED') {
      await pollOnlineJoinCommand()
    } else {
      scheduleOnlineCommandPoll()
    }
  } catch (error) {
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

function handleKeydown(event) {
  if (event.key !== 'Escape') return
  if (playerAccountDialogVisible.value) return
  if (onlineJoinVisible.value) closeOnlineJoin()
  else if (versionDialogVisible.value) closeVersionDialog()
  else if (pendingSelfRegistration.value) pendingSelfRegistration.value = null
  else closeDetail()
}

onMounted(async () => {
  const query = new URLSearchParams(window.location.search)
  mobileRegistrationToken.value = query.get('mobile_registration') || ''
  playerAccountBindingToken.value = query.get('account_binding') || ''
  playerAccountDialogVisible.value = Boolean(playerAccountBindingToken.value)
  if (playerAccountBindingToken.value) {
    const url = new URL(window.location.href)
    url.searchParams.delete('account_binding')
    window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`)
  }
  if (mobileRegistrationToken.value) {
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
  if (detailActionTimer) window.clearTimeout(detailActionTimer)
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <MobileRegistrationFlow v-if="mobileRegistrationToken" :token="mobileRegistrationToken" />
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
            <span class="queue-heading-separator" aria-hidden="true">·</span>
            <strong>当前共 {{ totalRegistrationCount }} 个登记</strong>
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
        <strong>队列数据暂未更新</strong>
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
        <strong>{{ markedSelfStatusTitle() }}</strong>
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
        <p>{{ loading ? '正在等待终端返回最新登记状态。' : '终端完成联网同步后，这里会显示现场机台的实时登记顺序。' }}</p>
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
          <strong>{{ outsideBusinessHours ? '不在营业时间' : '当前没有使用登记排队' }}</strong>
          <span v-if="outsideBusinessHours">
            {{ businessHoursClosingGrace
              ? '今日营业时间已结束，现场正在完成现有队列；收尾期间不再接收新的排队登记。'
              : registrationOpen
                ? '当前仍处于非营业时段；现场已手动开启登记排队，网页会继续显示这一提醒。'
                : '登记排队已经关闭；如现场手动开启，仍可继续使用登记排队。' }}
          </span>
          <span v-else>请按照现场顺序自然排队，并留意其他玩家的安排。</span>
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
            <button type="button" :aria-label="`查看${machine.name}详情`" @click="openMachineDetails(machine)">
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
            <span>请检查终端上传的数据是否包含机台 {{ machine.id }}。</span>
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
                      ? '本轮已超过 20 分钟，请确认机台是否仍在正常游玩，并留意后续队列安排。'
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
                    type="button"
                    @click.stop="openRegistration(machine, registration, `游玩位置 ${machine.id}`, null, machine.playing, null, true)"
                  >
                    <strong>{{ registration.displayId }}</strong>
                    <span>{{ registrationLabel(registration) }}</span>
                    <UserRoundCheck v-if="isMarkedRegistration(registration)" :size="14" aria-label="我的登记" />
                    <ChevronRight v-else :size="15" aria-hidden="true" />
                  </button>
                </div>
              </article>

              <article
                v-for="(position, index) in machine.waitingPositions"
                :key="position.positionId || `${machine.id}-${index}`"
                class="queue-position is-actionable"
                role="button"
                tabindex="0"
                @click="openPosition(machine, position)"
                @keydown.enter.self.prevent="openPosition(machine, position)"
                @keydown.space.self.prevent="openPosition(machine, position)"
              >
                <header>
                  <div class="queue-position-heading">
                    <span>{{ positionLabel(machine, position, index) }}</span>
                    <small>{{ positionEstimateText(position.estimatedWaitMinutes, position.registrations, machine) }}</small>
                  </div>
                  <ChevronRight :size="18" aria-hidden="true" />
                </header>
                <div class="queue-registration-grid">
                  <button
                    v-for="(registration, registrationIndex) in position.registrations"
                    :key="registration.registrationId || `${machine.id}-${index}-${registrationIndex}`"
                    class="queue-registration"
                    :class="[`is-${registrationTone(registration)}`, { 'is-self': isMarkedRegistration(registration) }]"
                    type="button"
                    @click.stop="openRegistration(machine, registration, `位置 ${machine.id}${index + 1}`, position.estimatedWaitMinutes, position.registrations, position.commonPlayPreview)"
                  >
                    <strong>{{ registration.displayId }}</strong>
                    <span>{{ registrationLabel(registration) }}</span>
                    <UserRoundCheck v-if="isMarkedRegistration(registration)" :size="14" aria-label="我的登记" />
                    <ChevronRight v-else :size="15" aria-hidden="true" />
                  </button>
                  <div v-if="position.commonPlayPreview" class="queue-registration is-preview">
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
        <span>队列状态仍可正常查看。服务器更新日志接口后，这里会自动恢复。</span>
        <button type="button" @click="loadCurrentLogs(true)">重新读取</button>
      </div>
      <div v-else-if="filteredLogs.length === 0" class="queue-logs-empty">
        <History :size="20" aria-hidden="true" />
        <strong>{{ currentLogs.length ? '这个筛选范围内没有日志' : '还没有可显示的队列日志' }}</strong>
        <span>新版终端同步后发生的队列变动会显示在这里。</span>
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
                <p>当前现场及各端运行信息</p>
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
                  : onlineJoinStep === 'CONFIRM' ? '确认登记信息'
                    : onlineJoinStep === 'EXISTING' ? '你已在排队'
                      : onlineJoinStep === 'PENDING' ? '正在提交登记'
                        : onlineJoinStep === 'REJECTED' ? '登记没有执行'
                          : '线上登记已完成' }}</h2>
                <p v-if="onlineJoinStep === 'LOOKUP'">使用已在现场终端建立的玩家资料</p>
                <p v-else-if="onlineJoinStep === 'SELF_LOADING'">正在使用当前登录的玩家资料</p>
                <p v-else-if="onlineJoinStep === 'SELF_ERROR'">当前登录资料暂时无法用于线上登记</p>
                <p v-else-if="onlineJoinStep === 'CONFIRM'">核对资料，并确认本次排队安排</p>
                <p v-else-if="onlineJoinStep === 'PENDING'">正在等待现场终端处理</p>
              </div>
              <button type="button" aria-label="关闭加入排队" title="关闭" @click="closeOnlineJoin">
                <X :size="20" aria-hidden="true" />
              </button>
            </header>

            <section v-if="onlineJoinStep === 'SELF_LOADING'" class="queue-online-result" aria-live="polite">
              <span class="queue-online-result-icon">
                <RefreshCw :size="23" class="spinning" />
              </span>
              <strong>正在确认你的玩家资料</strong>
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
                    <div class="queue-online-machine-options">
                      <button v-for="machine in group.machines" :key="machine.stableId || machine.id" type="button"
                        :class="{ active: onlineJoinMachineId === machine.id }" :disabled="!machine.available"
                        @click="selectOnlineJoinMachine(machine)">
                        <strong>{{ machine.name }}</strong>
                        <span>{{ machine.capacity === 1 ? '仅单人游玩' : onlineMachineEstimateText(machine) }}</span>
                        <span v-if="machine.capacity === 1">{{ onlineMachineEstimateText(machine) }}</span>
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
                    <div class="queue-online-machine-options">
                      <button v-for="machine in group.machines" :key="machine.stableId || machine.id" type="button"
                        :class="{ active: onlineJoinMachineId === machine.id }" :disabled="!machine.available"
                        @click="selectOnlineJoinMachine(machine)">
                        <strong>{{ machine.name }}</strong>
                        <span>{{ machine.capacity === 1 ? '仅单人游玩' : onlineMachineEstimateText(machine) }}</span>
                        <span v-if="machine.capacity === 1">{{ onlineMachineEstimateText(machine) }}</span>
                      </button>
                    </div>
                  </section>
                </div>
              </fieldset>

              <fieldset v-if="onlineJoinNeedsPreference" class="queue-online-options">
                <legend>本次游玩偏好</legend>
                <div class="queue-online-preference-options">
                  <button type="button" :class="{ active: onlineJoinPreference === 'OPEN_TO_JOIN' }"
                    @click="selectOnlineJoinPreference('OPEN_TO_JOIN')">
                    <strong>允许他人加入</strong>
                    <span>接受分配的共同游玩通常可以减少等待时间</span>
                  </button>
                  <button type="button" :class="{ active: onlineJoinPreference === 'SOLO' }"
                    @click="selectOnlineJoinPreference('SOLO')">
                    <strong>单人游玩</strong>
                    <span>独自占用一个等待位置</span>
                  </button>
                </div>
              </fieldset>

              <div v-if="onlineJoinSinglePlayerMachine" class="queue-online-capacity-notice">
                <Users :size="18" aria-hidden="true" />
                <p>
                  <strong>本次将使用“单人游玩”</strong>
                  <span>该机台仅能容纳一人游玩。本次登记不会修改玩家资料中的默认游玩偏好。</span>
                </p>
              </div>

              <div class="queue-online-check-in-notice">
                <TriangleAlert :size="18" aria-hidden="true" />
                <p>
                  <strong>须在 30 分钟内完成签到</strong>
                  <span>登记加入后会显示为“线上登记 · 待签到”。请到现场终端点击自己的登记并选择“已到场”。超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出排队。</span>
                  <span v-if="!onlineJoinProfile.setupComplete">这份玩家资料尚未补全通知偏好和 QQ 显示范围。线上登记可以先创建，但到场后须先在终端补全资料，才能签到。</span>
                </p>
              </div>
              <div v-if="onlineJoinAudience === 'OTHER'" class="queue-online-other-notice">
                <TriangleAlert :size="18" aria-hidden="true" />
                <p>
                  <strong>请确认你是在代本人创建</strong>
                  <span>线上登记只代表排队意向，不代表本人已到场。请把机台和签到规则告知对方，并由对方本人到现场完成签到。</span>
                </p>
              </div>
              <p v-if="onlineJoinError" class="queue-online-error" role="alert">{{ onlineJoinError }}</p>
              <div class="queue-online-actions">
                <button type="button" @click="backToOnlineLookup">返回查询</button>
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
              <strong>“{{ onlineJoinProfile.nickname }}”已有一份登记</strong>
              <p>当前位于{{ existingOnlineRegistrationText() }}。{{ onlineJoinExistingRegistration.online_registration_pending_check_in
                ? '这份线上登记仍需在创建后的 30 分钟内到现场终端完成签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出排队。'
                : '不能重复加入排队。' }}</p>
              <p v-if="onlineJoinExistingRegistration.online_registration_pending_check_in && !onlineJoinProfile.setupComplete">这份玩家资料尚未补全。到场后须先在终端补全资料，才能签到。</p>
              <button class="queue-online-primary" type="button"
                @click="openExistingOnlineRegistration(onlineJoinExistingRegistration)">
                {{ onlineJoinAudience === 'OTHER' ? '查看该登记' : '查看我的排队' }}
              </button>
            </div>

            <div v-else-if="onlineJoinStep === 'PENDING'" class="queue-online-result" aria-live="polite">
              <span class="queue-online-result-icon" aria-hidden="true">
                <RefreshCw :size="23" class="spinning" />
              </span>
              <strong>{{ onlineJoinTerminalApplied ? '终端已保存，正在同步队列' : '正在等待现场终端确认' }}</strong>
              <p>{{ onlineJoinTerminalApplied
                ? '终端已确认创建，正在刷新最新队列位置。'
                : '请保持页面打开。终端处理完成后，这里会显示最终结果；重复点击不会建立多份登记。' }}</p>
              <p v-if="!onlineJoinProfile?.setupComplete">终端确认创建后，到场时须先补全玩家资料，才能签到。</p>
              <button class="queue-online-secondary" type="button" @click="closeOnlineJoin">在后台等待</button>
            </div>

            <div v-else-if="onlineJoinStep === 'REJECTED'" class="queue-online-result is-rejected" aria-live="polite">
              <span class="queue-online-result-icon" aria-hidden="true"><TriangleAlert :size="23" /></span>
              <strong>这次线上登记没有执行</strong>
              <p>{{ onlineJoinResultDetail }}</p>
              <button class="queue-online-primary" type="button" @click="backToOnlineLookup">重新查询</button>
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
                  <span>在现场终端点击自己的登记并选择“已到场”。超过 30 分钟，或轮到进入游玩位置时仍未签到，登记会自动退出排队。</span>
                  <span v-if="!onlineJoinProfile?.setupComplete">到场后须先在终端补全玩家资料，才能签到。</span>
                </p>
              </div>
              <button class="queue-online-primary" type="button" @click="closeOnlineJoin">查看队列</button>
            </div>
          </section>
        </div>
      </Transition>

      <Transition name="queue-dialog">
        <div v-if="selectedDetail" class="queue-detail-backdrop" @click.self="closeDetail">
          <section class="queue-detail-dialog" role="dialog" aria-modal="true" :aria-label="selectedDetail.title">
            <header class="queue-detail-header">
              <div>
                <h2>{{ selectedDetail.title }}</h2>
                <p v-if="selectedDetail.kind === 'registration'">{{ selectedDetail.locationLabel }}</p>
                <p v-else-if="selectedDetail.kind === 'position'">{{ selectedDetail.isPlaying ? selectedDetail.playingText : positionEstimateText(selectedDetail.estimate, selectedDetail.registrations, selectedDetail.machine) }}</p>
                <p v-else>机台 {{ selectedDetail.machine.id }}·{{ machineGroupName(selectedDetail.machine) }}</p>
              </div>
              <button type="button" aria-label="关闭详情" title="关闭" @click="closeDetail">
                <X :size="20" aria-hidden="true" />
              </button>
            </header>

            <template v-if="selectedDetail.kind === 'machine'">
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
                  机台已停止使用 · {{ stopReasonLabel(
                    selectedDetail.machine.stopReason,
                    selectedDetail.machine.stopReasonDetail
                  ) }}
                </span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.temporarilyAway)" class="is-absence">包含暂时离开</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.deferredOnce)" class="is-absence">包含暂缓一次</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.noShowCount > 0)" class="is-warning">包含未到场记录</span>
                <span v-if="selectedDetail.registrations.some((registration) => registration.onlineRegistrationPendingCheckIn)" class="is-online">包含待签到登记</span>
                <span v-if="selectedDetail.commonPlayPreview">预计与“{{ selectedDetail.commonPlayPreview.displayId }}”共同游玩</span>
              </div>
              <div class="queue-detail-registration-list">
                <button v-for="registration in selectedDetail.registrations"
                  :key="registration.registrationId || registration.displayId" type="button"
                  :class="`is-${registrationTone(registration)}`"
                  @click="openRegistrationFromPosition(registration)">
                  <span>
                    <strong>{{ registration.displayId }}</strong>
                    <small>{{ registrationLabel(registration) }}</small>
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
            </template>

            <template v-else>
              <div class="queue-detail-pills">
                <span v-if="selectedDetail.registration.onlineRegistrationPendingCheckIn" class="is-online">
                  线上登记 · 待签到
                </span>
                <span :class="{ 'is-absence': absenceLabel(selectedDetail.registration) }">
                  {{ absenceLabel(selectedDetail.registration) || preferenceLabel(selectedDetail.registration) }}
                </span>
                <span>{{ registrationTypeLabel(selectedDetail.registration) }}</span>
                <span v-if="registrationPartnerText(selectedDetail)">
                  {{ registrationPartnerText(selectedDetail) }}
                </span>
                <span v-if="selectedDetail.registration.noShowCount > 0" class="is-warning">
                  未到场 {{ selectedDetail.registration.noShowCount }} 次
                </span>
              </div>
              <dl class="queue-detail-metadata">
                <div>
                  <dt>游玩偏好</dt>
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
                <div v-if="selectedDetail.registration.onlineRegistrationPendingCheckIn">
                  <dt>签到规则</dt>
                  <dd v-if="hasRestartedOnlineCheckInWindow(selectedDetail.registration)">
                    机台恢复正常使用后重新获得的 30 分钟内；轮到时仍未签到也会自动退出
                  </dd>
                  <dd v-else>创建登记后 30 分钟内；轮到时仍未签到也会自动退出</dd>
                </div>
                <div v-if="selectedDetail.estimatedWaitMinutes !== null || selectedDetail.registration.onlineRegistrationPendingCheckIn">
                  <dt>预计游玩</dt>
                  <dd>{{ positionEstimateText(selectedDetail.estimatedWaitMinutes, selectedDetail.locationRegistrations, selectedDetail.machine) }}</dd>
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
                  <dd>停止使用 · {{ stopReasonLabel(
                    selectedDetail.machine.stopReason,
                    selectedDetail.machine.stopReasonDetail
                  ) }}</dd>
                </div>
                <div>
                  <dt>创建时间</dt>
                  <dd>{{ fullTimeText(selectedDetail.registration.createdAt) }}</dd>
                </div>
                <div>
                  <dt>上次游玩</dt>
                  <dd>{{ fullTimeText(selectedDetail.registration.lastPlayedAt, '尚未游玩') }}</dd>
                </div>
              </dl>
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
                  <p v-else-if="!playerAccountQueueState.queue?.remote_actions" class="queue-detail-action-warning">
                    现场未开启网站远程操作，当前只能查看状态。
                  </p>
                  <template v-if="detailActionMode === 'transfer'">
                    <strong class="queue-detail-action-title">选择要转至的机台</strong>
                    <div class="queue-detail-action-choices">
                      <button v-for="machine in playerAccountQueueState.queue.machines.filter((item) => item.id !== accountQueueRegistrationFor(selectedDetail.registration).machine_id)"
                        :key="machine.id" type="button" :disabled="machine.available !== true || detailActionSubmitting"
                        :class="{ active: detailActionTargetMachineId === machine.id }"
                        @click="detailActionTargetMachineId = machine.id">
                        {{ machine.name }}
                      </button>
                    </div>
                    <p v-if="detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration))" class="queue-detail-action-detail">
                      {{ detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration)).detail }}
                    </p>
                    <div class="queue-detail-action-confirm">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                      <button class="primary" type="button"
                        :disabled="!detailActionTargetMachineId || detailActionSubmitting"
                        @click="submitDetailQueueAction(accountQueueRegistrationFor(selectedDetail.registration), 'TRANSFER_MACHINE', { target_machine_id: detailActionTargetMachineId, expected_target_machine_stable_id: playerAccountQueueState.queue.machines.find((machine) => machine.id === detailActionTargetMachineId)?.stable_id })">
                        {{ detailTransferPrompt(accountQueueRegistrationFor(selectedDetail.registration))?.confirm || '确认转至其他机台' }}
                      </button>
                    </div>
                  </template>
                  <template v-else-if="detailActionMode === 'preference'">
                    <strong class="queue-detail-action-title">选择本次游玩偏好</strong>
                    <p class="queue-detail-action-detail">这里只修改本次排队的偏好，不会改变玩家资料中的默认偏好。</p>
                    <div class="queue-detail-action-choices is-two">
                      <button type="button" :class="{ active: detailActionPreference === 'SOLO' }"
                        :disabled="detailActionSubmitting" @click="detailActionPreference = 'SOLO'">单人游玩</button>
                      <button type="button" :class="{ active: detailActionPreference === 'OPEN_TO_JOIN' }"
                        :disabled="detailActionSubmitting" @click="detailActionPreference = 'OPEN_TO_JOIN'">允许他人加入</button>
                    </div>
                    <div class="queue-detail-action-confirm">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                      <button class="primary" type="button" :disabled="detailActionSubmitting"
                        @click="submitDetailQueueAction(accountQueueRegistrationFor(selectedDetail.registration), 'CHANGE_PLAY_PREFERENCE', { preference: detailActionPreference })">
                        确认修改游玩偏好
                      </button>
                    </div>
                  </template>
                  <template v-else-if="['defer', 'temporary_leave', 'cancel_defer', 'cancel_temporary_leave', 'leave'].includes(detailActionMode)">
                    <template v-if="detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode)">
                      <strong class="queue-detail-action-title">{{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).title }}</strong>
                      <p class="queue-detail-action-detail">{{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).detail }}</p>
                      <p v-if="detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).note" class="queue-detail-action-note">
                        {{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).note }}
                      </p>
                    </template>
                    <div class="queue-detail-action-confirm">
                      <button type="button" :disabled="detailActionSubmitting" @click="detailActionMode = null">取消</button>
                      <button class="primary" :class="{ 'is-danger': detailActionMode === 'leave' }" type="button"
                        :disabled="detailActionSubmitting || !detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode)"
                        @click="submitDetailQueueAction(accountQueueRegistrationFor(selectedDetail.registration), detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode).operation)">
                        {{ detailActionPrompt(accountQueueRegistrationFor(selectedDetail.registration), detailActionMode)?.confirm }}
                      </button>
                    </div>
                  </template>
                  <div v-else class="queue-detail-action-buttons">
                    <template v-if="!accountQueueRegistrationFor(selectedDetail.registration).online_registration_pending_check_in">
                      <button v-if="accountQueueRegistrationFor(selectedDetail.registration).deferred_once" type="button"
                        :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="openDetailAction('cancel_defer')">取消暂缓一次</button>
                      <button v-else-if="playerAccountQueueState.queue?.queue_rules?.allow_defer_one_round" type="button"
                        :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="openDetailAction('defer')">暂缓一次</button>
                      <button v-if="accountQueueRegistrationFor(selectedDetail.registration).temporarily_away" type="button"
                        :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="openDetailAction('cancel_temporary_leave')">取消暂时离开</button>
                      <button v-else-if="playerAccountQueueState.queue?.queue_rules?.allow_temporary_leave" type="button"
                        :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="openDetailAction('temporary_leave')">暂时离开</button>
                      <button v-if="accountQueueRegistrationFor(selectedDetail.registration).position !== 'PLAYING' && playerAccountQueueState.queue.machines.some((machine) => machine.id !== accountQueueRegistrationFor(selectedDetail.registration).machine_id && machine.available)" type="button"
                        :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="detailActionTargetMachineId = ''; openDetailAction('transfer')">转至其他机台</button>
                      <button v-if="accountQueueRegistrationFor(selectedDetail.registration).machine_capacity > 1" type="button"
                        :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="detailActionPreference = accountQueueRegistrationFor(selectedDetail.registration).preference; openDetailAction('preference')">修改游玩偏好</button>
                    </template>
                    <button class="is-danger" type="button" :disabled="!playerAccountQueueState.queue?.remote_actions || detailActionSubmitting" @click="openDetailAction('leave')">退出排队</button>
                  </div>
                  <p v-if="detailActionError" class="queue-detail-action-error" role="alert">{{ detailActionError }}</p>
                  <p v-if="detailActionNotice" class="queue-detail-action-notice" role="status">{{ detailActionNotice }}</p>
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
                标记使用的昵称、QQ 号和公开登记标识仅保存在此浏览器中。
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
.queue-heading-separator { display: inline-flex; width: 1em; flex: 0 0 1em; justify-content: center; color: var(--queue-tertiary); }
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
.queue-self-main > p { margin: 3px 0 0; color: var(--queue-secondary); font-size: 11px; line-height: 1.6; }
.queue-self-facts { display: flex; margin-top: 9px; flex-wrap: wrap; gap: 6px; }
.queue-self-facts span { display: flex; padding: 4px 7px; align-items: center; gap: 4px; border-radius: 6px; color: var(--queue-secondary); background: color-mix(in srgb, var(--queue-card) 82%, transparent); font-size: 10px; }
.queue-self-clear { padding: 7px 8px; border: 0; color: var(--queue-secondary); background: transparent; cursor: pointer; font-size: 11px; }
.queue-self-account-badge { padding: 5px 7px; border-radius: 6px; color: var(--queue-blue); background: var(--queue-soft-blue); font-size: 10px; font-weight: 600; white-space: nowrap; }

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
.queue-registration.is-self { border-color: color-mix(in srgb, var(--queue-blue) 54%, var(--queue-separator)); }
.queue-registration > svg { position: absolute; top: 50%; right: 8px; color: var(--queue-tertiary); transform: translateY(-50%); }
.queue-registration.is-self > svg { color: var(--queue-blue); }
.queue-registration strong { overflow: hidden; font-size: 14px; font-weight: 570; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.queue-registration span { margin-top: 4px; color: var(--queue-secondary); font-size: 10px; line-height: 1.35; }
.queue-registration.is-absence span { color: var(--queue-orange); font-weight: 570; }
.queue-registration.is-warning span { color: var(--queue-red); font-weight: 570; }
.queue-registration.is-online span { color: var(--queue-online); font-weight: 570; }
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
.queue-detail-dialog, .queue-confirm-dialog { --queue-card: #fff; --queue-position: #f5f5f7; --queue-text: #1d1d1f; --queue-secondary: #6e6e73; --queue-tertiary: #8e8e93; --queue-separator: #d2d2d7; --queue-blue: #007aff; --queue-soft-blue: #eaf3ff; --queue-orange: #b85c00; --queue-soft-orange: #fff1dc; --queue-online: #087f73; --queue-soft-online: #e8f7f4; width: min(100%, 480px); max-height: min(680px, calc(100vh - 36px)); padding: 20px; overflow-y: auto; border: 1px solid var(--queue-separator); border-radius: 16px; color: var(--queue-text); background: var(--queue-card); box-shadow: 0 20px 54px rgba(0, 0, 0, .22); }
:global(html.dark .queue-detail-dialog), :global(html.dark .queue-confirm-dialog) { --queue-card: #1c1c1e; --queue-position: #242426; --queue-text: #f5f5f7; --queue-secondary: #a1a1a6; --queue-tertiary: #8e8e93; --queue-separator: #38383a; --queue-blue: #0a84ff; --queue-soft-blue: #142b44; --queue-orange: #ffb35c; --queue-soft-orange: #3b2b13; --queue-online: #63d8ca; --queue-soft-online: #143632; }
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
.queue-detail-action-buttons button, .queue-detail-action-confirm button { min-height: 40px; padding: 7px 9px; border: 1px solid var(--queue-separator); border-radius: 8px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; font-size: 11px; line-height: 1.35; }
.queue-detail-action-buttons button.is-danger, .queue-detail-action-confirm button.is-danger { color: var(--queue-red); }
.queue-detail-action-buttons button:disabled, .queue-detail-action-confirm button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-detail-action-title { display: block; margin-top: 13px; font-size: 12px; font-weight: 620; }
.queue-detail-action-detail, .queue-detail-action-note { margin: 7px 0 0; color: var(--queue-secondary); font-size: 10px; line-height: 1.6; }
.queue-detail-action-note { color: var(--queue-orange); }
.queue-detail-action-choices { display: grid; margin-top: 9px; grid-template-columns: repeat(auto-fit, minmax(100px, 1fr)); gap: 7px; }
.queue-detail-action-choices.is-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.queue-detail-action-choices button { min-height: 40px; padding: 7px 9px; border: 1px solid var(--queue-separator); border-radius: 8px; color: var(--queue-text); background: var(--queue-position); cursor: pointer; font-size: 11px; }
.queue-detail-action-choices button.active { border-color: var(--queue-blue); color: var(--queue-blue); background: var(--queue-soft-blue); }
.queue-detail-action-choices button:disabled { color: var(--queue-tertiary); background: var(--queue-disabled); cursor: default; }
.queue-detail-action-confirm { display: grid; margin-top: 10px; grid-template-columns: 1fr 1.35fr; gap: 7px; }
.queue-detail-action-confirm button.primary { border-color: var(--queue-blue); color: #fff; background: var(--queue-blue); }
.queue-detail-action-confirm button.primary.is-danger { border-color: var(--queue-red); background: var(--queue-red); }
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
