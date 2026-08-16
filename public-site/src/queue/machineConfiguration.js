const GAME_TYPES = new Set([
  'MAIMAI_DX',
  'CHUNITHM',
  'ONGEKI',
  'DANCE_CUBE',
  'TAIKO_NO_TATSUJIN',
  'OTHER'
])

const SERVERS = new Set([
  'CHINA',
  'INTERNATIONAL',
  'JAPAN',
  'DABING',
  'RINNET',
  'OTHER',
  'HIDDEN'
])

const SERVER_CONFIGURABLE_GAME_TYPES = new Set(['MAIMAI_DX', 'CHUNITHM', 'ONGEKI'])

const GAME_TYPE_LABELS = Object.freeze({
  MAIMAI_DX: '舞萌 DX',
  CHUNITHM: '中二节奏',
  ONGEKI: 'Ongeki',
  DANCE_CUBE: '舞立方',
  TAIKO_NO_TATSUJIN: '太鼓达人',
  OTHER: '其他'
})

const SERVER_LABELS = Object.freeze({
  CHINA: '中国',
  INTERNATIONAL: '国际',
  JAPAN: '日本',
  DABING: '大饼',
  RINNET: 'RinNET',
  OTHER: '其他',
  HIDDEN: '隐藏'
})

function normalizedString(value) {
  return typeof value === 'string' ? value.trim() : ''
}

const MIDDLE_DOT_SPACING_REGEX = /[^\S\r\n\u2028\u2029]*·[^\S\r\n\u2028\u2029]*/g

export function compactMiddleDots(value) {
  return typeof value === 'string' ? value.replace(MIDDLE_DOT_SPACING_REGEX, '·') : value
}

const HAN_MIDDLE_DOT_REGEX = /(\p{Script=Han})·(?=\p{Script=Han})/gu

export function formatMiddleDots(value) {
  return typeof value === 'string'
    ? compactMiddleDots(value).replace(HAN_MIDDLE_DOT_REGEX, '$1 · ')
    : value
}

function normalizedInteger(value, fallback) {
  const number = Number(value)
  return Number.isInteger(number) && number >= 1 && number <= 120 ? number : fallback
}

function inferredRemark(name, machineId) {
  const normalizedName = compactMiddleDots(normalizedString(name))
  const suffix = machineId ? `·机台 ${machineId}` : ''
  if (suffix && normalizedName.endsWith(suffix)) {
    return normalizedName.slice(0, -suffix.length).trim()
  }
  return normalizedName
}

export function normalizeMachineConfiguration(source, fallback = {}) {
  const machine = source && typeof source === 'object' ? source : {}
  const raw = machine.configuration ?? machine.machine_configuration ?? machine
  const configuration = raw && typeof raw === 'object' ? raw : {}
  const gameTypeValue = String(
    configuration.game_type ?? configuration.gameType ?? 'MAIMAI_DX'
  ).toUpperCase()
  const gameType = GAME_TYPES.has(gameTypeValue) ? gameTypeValue : 'MAIMAI_DX'
  const serverValue = String(
    configuration.server ?? configuration.server_region ?? configuration.serverRegion ?? 'HIDDEN'
  ).toUpperCase()
  const server = SERVER_CONFIGURABLE_GAME_TYPES.has(gameType) && SERVERS.has(serverValue)
    ? serverValue
    : 'HIDDEN'
  const capacityValue = Number(configuration.capacity ?? machine.capacity)
  const capacity = capacityValue === 1 ? 1 : 2
  const remark = normalizedString(machine.remark ?? configuration.remark) ||
    normalizedString(fallback.remark) ||
    inferredRemark(machine.name ?? fallback.name, fallback.id) ||
    (fallback.id ? `机台 ${fallback.id}` : '机台')
  const customGameType = normalizedString(
    configuration.custom_game_type ?? configuration.customGameType
  )
  const customServer = normalizedString(
    configuration.custom_server ?? configuration.customServer
  )
  const gameVersion = normalizedString(
    configuration.game_version ?? configuration.gameVersion
  )
  const gameVersionVisible = (
    configuration.game_version_visible === true ||
    configuration.gameVersionVisible === true ||
    configuration.show_game_version === true ||
    configuration.showGameVersion === true
  ) && Boolean(gameVersion)

  return {
    remark,
    gameType,
    customGameType,
    server,
    customServer,
    gameVersion,
    gameVersionVisible,
    capacity,
    soloRoundMinutes: normalizedInteger(
      configuration.solo_round_minutes ?? configuration.soloRoundMinutes,
      12
    ),
    sharedRoundMinutes: normalizedInteger(
      configuration.shared_round_minutes ?? configuration.sharedRoundMinutes,
      15
    )
  }
}

export function machineGameTypeName(configuration) {
  if (configuration.gameType === 'OTHER') {
    return configuration.customGameType || GAME_TYPE_LABELS.OTHER
  }
  return GAME_TYPE_LABELS[configuration.gameType] || GAME_TYPE_LABELS.MAIMAI_DX
}

export function machineSupportsServer(configuration) {
  return SERVER_CONFIGURABLE_GAME_TYPES.has(configuration.gameType)
}

export function machineServerName(configuration) {
  if (configuration.server === 'OTHER') {
    return configuration.customServer || SERVER_LABELS.OTHER
  }
  return SERVER_LABELS[configuration.server] || SERVER_LABELS.HIDDEN
}
