import { formatWaitAmount } from './waitEstimateCopy.js'
// Wording for letting the system arrange a registration between the two machines of a pair.
// Several pages share it, so that every page says the same thing in the same words. The
// approved texts are listed in docs/releases/dual-machine-queue-copy.md; the vocabulary is in
// docs/copywriting-guide.md.
//
// The pages never describe this as queueing at two machines: a registration queues at its
// current machine, and the system may move it to the other one. There is no setting in the
// player profile; the only switch is a tick box on one registration.

// The registration's own machine is "当前机台"; only the other machine is named.
export function pairedQueueTickBoxLabel(pairedMachineName) {
  return `允许系统在当前机台和 ${pairedMachineName} 之间安排登记`
}

export const PAIRED_QUEUE_TICK_BOX_NOTE = '开启后，这份登记将在符合条件时自动转至能够更早安排游玩的机台。'

const PAIRED_QUEUE_NOT_THIS_ROUND = '这份登记游玩一轮之前，不会被安排到另一机台。'

// Shown instead of the usual note while the box is being turned on for a registration that
// is already waiting: that only counts after the registration has played a round.
export const PAIRED_QUEUE_LATER_NOTE = `${PAIRED_QUEUE_TICK_BOX_NOTE}${PAIRED_QUEUE_NOT_THIS_ROUND}`

export const PAIRED_QUEUE_FIXED_PAIR_REASON = '固定组合只能在当前机台游玩。'

export const PAIRED_QUEUE_DETAILS_BUTTON_LABEL = '查看说明'
export const PAIRED_QUEUE_DETAILS_TITLE = '自动在两台机台之间安排登记'
export const PAIRED_QUEUE_DETAILS_CLOSE = '我知道了'

// Under the close button while the explanation that opened by itself cannot be closed yet.
export function pairedQueueDetailsCountdown(seconds) {
  return `${seconds} 秒后可以关闭`
}

// Where the tick box of a registration that exists can be cleared again.
export const PAIRED_QUEUE_CANCEL_SENTENCE = '可以在“更改游玩偏好”中取消。'

// How long a preview is shown at least before the registration is moved, in minutes. Players
// are told this number; the other limits of the rules are internal and stay unnamed. It has to
// equal the lead time the terminal applies (PairedArrangementRules.leadMillis in queue-core).
export const PAIRED_QUEUE_LEAD_MINUTES = 12

function pairedQueuePreviewSentence(pairedMachineName) {
  return `${pairedMachineName} 能够更早安排这份登记游玩时，其等待顺序中会显示预览。`
}

// The explanation: shown once per player profile when the box is first turned on, and at any
// time behind the question mark. A string is a paragraph; an object is a sentence followed
// by a list. The terminal shows the same seven paragraphs.
export function pairedQueueDetailsBlocks({ pairedMachineName }) {
  return [
    '开启后，这份登记仍在当前机台排队。',
    `${pairedQueuePreviewSentence(pairedMachineName)}预览持续显示至少 ${PAIRED_QUEUE_LEAD_MINUTES} 分钟后，登记才会自动转至 ${pairedMachineName} 并进入游玩位置。`,
    '这份登记可能比当前机台的预计时间更早被安排游玩。预览显示一段时间后，系统会发送排队通知。请留意现场提示和通知，合理安排时间。',
    `自动转至 ${pairedMachineName} 后未到场时，登记会回到当前机台原来的等待顺序，未到场记录不会增加，这项选择同时取消。`,
    '游玩一轮之后，登记会保留在上次游玩的机台，这项选择保持开启。',
    {
      lead: '以下情况，登记不会被安排到另一机台：',
      items: [
        '暂缓一次、轮空一次、暂时离开和待签到期间。',
        '等待顺序被调整、登记从游玩位置撤回，或在排队期间开启这项选择之后，直到这份登记游玩一轮。',
        '固定组合。'
      ]
    },
    PAIRED_QUEUE_CANCEL_SENTENCE
  ]
}

// A moment as the wall-clock time of this device, written HH:mm as the terminal writes it.
export function pairedQueueClockTime(millis) {
  const date = new Date(millis)
  const twoDigits = (value) => String(value).padStart(2, '0')
  return `${twoDigits(date.getHours())}:${twoDigits(date.getMinutes())}`
}

// The sentence that tells of a preview announcing a move, as the registration's own entry shows
// it. [notBeforeMillis] is the earliest moment of that move, absent while the terminal holds
// the count, and [nowMillis] is the page's current time. It is the part set in colour.
// [previewPosition] names the position at which the other machine shows the preview, such as
// B1, as the terminal does (maintainer, 2026-11-25: "合理引用位置编号"); without it the machine
// is named.
export function pairedQueueAnnouncementSentence({
  pairedMachineName,
  notBeforeMillis = null,
  nowMillis = Date.now(),
  previewPosition = null
}) {
  const previewShown = previewPosition
    ? `${pairedMachineName} 的位置 ${previewPosition} 已显示这份登记的预览`
    : `${pairedMachineName} 的等待顺序中已显示这份登记的预览`
  // A machine name is set off by a space (copy guide); 该位置 is not.
  const destination = previewPosition ? '该位置' : ` ${pairedMachineName}`
  const hasMoment = typeof notBeforeMillis === 'number' && Number.isFinite(notBeforeMillis)
  if (hasMoment && notBeforeMillis > nowMillis) {
    return `${previewShown}，最早 ${pairedQueueClockTime(notBeforeMillis)} 之后，这份登记可能自动转至${destination}，请合理安排时间。`
  }
  if (hasMoment) {
    return `${previewShown}，这份登记随时可能自动转至${destination}，请合理安排时间。`
  }
  return `${previewShown}。`
}

// One small line on a registration whose box is on: what is on, what to expect, and where to
// clear it, in three parts so that the sentence about a running announcement can be set in
// colour. [skipsThisRound] is the state in which the registration stays at its own machine
// until it has played a round. [announced] says that a preview at the other machine announces
// a move.
export function pairedQueueStatusParts({
  pairedMachineName,
  skipsThisRound,
  announced = false,
  notBeforeMillis = null,
  nowMillis = Date.now(),
  previewPosition = null
}) {
  const allowed = `已允许系统在当前机台和 ${pairedMachineName} 之间安排登记。`
  if (skipsThisRound) {
    return { before: `${allowed}${PAIRED_QUEUE_NOT_THIS_ROUND}`, highlighted: '', after: PAIRED_QUEUE_CANCEL_SENTENCE }
  }
  if (announced) {
    return {
      before: allowed,
      highlighted: pairedQueueAnnouncementSentence({ pairedMachineName, notBeforeMillis, nowMillis, previewPosition }),
      after: PAIRED_QUEUE_CANCEL_SENTENCE
    }
  }
  return {
    before: `${allowed}${pairedQueuePreviewSentence(pairedMachineName)}`,
    highlighted: '',
    after: PAIRED_QUEUE_CANCEL_SENTENCE
  }
}

export function pairedQueueStatusLine(options) {
  const { before, highlighted, after } = pairedQueueStatusParts(options)
  return `${before}${highlighted}${after}`
}

// The small line under a registration on the queue page while a preview at the other machine
// announces a move. It stands in place of the play preference and is set in colour. The target
// is the preview's position, such as B1, or the machine when no position is shown.
export function pairedQueueEntryNotice(target) {
  return `可能转至 ${target}`
}

// What the confirmation of a transfer by hand adds for a registration whose box is on. Within
// the pair the choice stays; a transfer to any other machine ends it.
export function pairedQueueTransferNotice({
  displayId,
  sourceMachineName,
  pairedMachineName,
  destinationIsPairedMachine,
  destinationMachineName
}) {
  const allowed = `“${displayId}”已允许系统在 ${sourceMachineName} 和 ${pairedMachineName} 之间安排登记。`
  return destinationIsPairedMachine
    ? `${allowed}登记留在 ${sourceMachineName} 排队时，也可能被更早安排到 ${pairedMachineName} 游玩；转至 ${pairedMachineName} 后，只能加入其队尾。`
    : `${allowed}转至 ${destinationMachineName} 后，将取消系统在 ${sourceMachineName} 和 ${pairedMachineName} 之间的安排。`
}

// The grey entry that stands for a registration of the other machine.
export const PAIRED_QUEUE_PREVIEW_TITLE = '另一机台登记的预览'

// One sentence per line: the page shows this text with its line breaks.
export function pairedQueuePreviewExplanation({ homeMachineName, previewMachineName }) {
  return [
    `这份登记在 ${homeMachineName} 排队，并已允许系统在 ${homeMachineName} 和 ${previewMachineName} 之间安排。`,
    `按当前等待顺序和预计游玩时间，${previewMachineName} 能够更早安排这份登记游玩。`,
    `预览持续显示至少 ${PAIRED_QUEUE_LEAD_MINUTES} 分钟后，登记才会自动转至 ${previewMachineName}。`,
    '预览不会改变真实队列。'
  ].join('\n')
}

// The pill in a registration's details.
export const PAIRED_QUEUE_PILL = '已允许系统在两台机台之间安排'

// A wait estimate that the terminal marked as possibly inaccurate (`estimated_wait_uncertain`):
// the system may move registrations between the two machines of a pair, so the minutes are the
// earliest time. [minutes] is a whole number of minutes. The texts are approved in
// docs/releases/wait-estimate-2026-11.md, section 三, “可能不准确时怎样显示”.
export function pairedQueueUncertainEstimateText(minutes) {
  return minutes <= 0 ? '可能很快可以游玩' : `${formatWaitAmount(minutes, true)}后可以游玩`
}

// Said once in a detail view wherever such an estimate is shown; list cells leave it out.
export const PAIRED_QUEUE_UNCERTAIN_ESTIMATE_NOTE = '受系统在两台机台之间安排登记的影响，等待时间可能不准确，请以现场实际情况为准。'

// Profiles whose player has been shown the explanation since this page was opened. The server
// keeps the lasting record on the profile; this only spares a second popup before the page
// has read that record again. Nothing is written to the browser's storage.
const explanationShownKeys = new Set()

export function pairedQueueExplanationShownHere(profileKey) {
  return explanationShownKeys.has(profileKey)
}

export function rememberPairedQueueExplanationShown(profileKey) {
  explanationShownKeys.add(profileKey)
}
