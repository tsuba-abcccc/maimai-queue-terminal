// 单人游玩的登记允许后方的登记越过 (maintainer, 2026-11-26; docs/releases/solo-pass-plan-2026-11.md,
// sections 十一 and 十二). A registration that plays alone may let an open registration behind it
// pass over it and play with the open registration ahead. The real waiting order stays as it is;
// only the grouping into positions changes. The texts are the terminal's, word for word.
// A fixed pair may do the same (2026-11-27), see below.

// The tick box sits inside the 单人游玩 option: a sentence of body text, then the small text.
export const SOLO_PASS_TICK_BOX_LABEL =
  '前方的登记允许他人加入，却因这份登记单人游玩而独自占用一个等待位置时，允许后方的登记越过这份登记与前方的登记共同游玩。'
export const SOLO_PASS_TICK_BOX_NOTE =
  '后方的登记也允许他人加入时，可以越过这份登记与前方的登记组成共同游玩，使队列加速推进。越过不会推迟这份登记及其他登记的游玩轮次，但这份登记可能因前方一轮改为两人游玩而稍晚进入游玩位置。'

// A fixed pair may let the registrations behind pass as well (maintainer, 2026-11-27;
// docs/releases/fixed-pair-pass-plan-2026-11.md, sections 四 and 五). One choice for both
// registrations of the pair; either member may change it. The tick box stands with the fixed
// pair's state in 更改游玩偏好.
export const FIXED_PAIR_PASS_TICK_BOX_LABEL =
  '前方的登记允许他人加入，却因这组固定组合排在其后而独自占用一个等待位置时，允许后方的登记越过这组固定组合与前方的登记共同游玩。'
export const FIXED_PAIR_PASS_TICK_BOX_NOTE =
  '后方的登记也允许他人加入时，可以越过这组固定组合与前方的登记组成共同游玩，使队列加速推进。越过不会推迟这组固定组合及其他登记的游玩轮次，但这组固定组合可能因前方一轮改为两人游玩而稍晚进入游玩位置。'

// Whether the terminal lets a fixed pair allow passing, and so accepts SET_ALLOWS_PASSING from
// a member of one (capabilities.fixed_pair_passing_supported of the status API). Older servers
// and terminals leave it out: the pair's tick box is then not shown.
export function terminalSupportsFixedPairPassing(capabilities) {
  return capabilities?.fixed_pair_passing_supported === true
}

// The explanation of letting others pass (plan, section 四): it opens by itself the first time
// a player profile turns a passing tick box on, and at any time from the question mark beside
// the box, the same control as beside the tick box for two machines (PairedQueueTickBox.vue,
// PAIRED_QUEUE_DETAILS_BUTTON_LABEL), as on the terminal. The close button and the countdown
// under it are those of the explanation for two machines.
export const PASSING_DETAILS_TITLE = '允许后方的登记越过'

// The paragraphs of the explanation, for one registration or for a fixed pair ([fixedPair]).
// The terminal shows the same four. Its last one names the terminal's home page and, for a
// fixed pair, its position menu; the website has neither, so the last paragraph names no page
// and tells of 更改游玩偏好 only.
export function passingDetailsBlocks({ fixedPair = false } = {}) {
  const subject = fixedPair ? '这组固定组合' : '这份登记'
  return [
    `开启后，${subject}仍在原来的位置排队，真实的等待顺序不变。`,
    `前方的登记允许他人加入，却因${subject}而独自占用一个等待位置时，后方同样允许他人加入的登记可以越过${subject}，与前方的登记共同游玩。`,
    `越过不会使${subject}晚一轮游玩；系统在每次越过前都会核对，会使任何登记晚一轮时不会越过。前方一轮由一人游玩改为两人游玩，${subject}进入游玩位置的时间可能稍晚。`,
    `越过${subject}的登记会显示“将越过”。可以随时在“更改游玩偏好”中取消。`
  ]
}

// Profiles whose player has been shown that explanation since this page was opened, kept as
// for the explanation of the two machines (pairedQueueCopy.js): the server keeps the lasting
// record on the profile (passing_introduced); this only spares a second popup before the page
// has read that record again. Nothing is written to the browser's storage.
const passingExplanationShownKeys = new Set()

export function passingExplanationShownHere(profileKey) {
  return passingExplanationShownKeys.has(profileKey)
}

export function rememberPassingExplanationShown(profileKey) {
  passingExplanationShownKeys.add(profileKey)
}

// Whether the terminal has declared that it carries out [operation] (capabilities.queue_operations
// of the status API). Older servers and terminals declare nothing: the operation is then not
// offered.
export function terminalSupportsQueueOperation(capabilities, operation) {
  const operations = capabilities?.queue_operations
  return Array.isArray(operations) && operations.includes(operation)
}

// The small line of a waiting registration that will pass over others, in place of its play
// preference: the first one passed over by name, and how many there are when more than one.
export function soloPassTileText(firstPassedName, passedCount) {
  if (!firstPassedName || !(passedCount >= 1)) return null
  return passedCount > 1
    ? `将越过“${firstPassedName}”等 ${passedCount} 份登记`
    : `将越过“${firstPassedName}”`
}

// For every registration that allows passing, alone or as one of a fixed pair (the passer then
// names both registrations of the pair), as the status page has normalized the machines
// (playing, waitingPositions[].registrations, each with registrationId, displayId,
// allowsPassing and passingOver): what the passing means for it right now. A registration named
// in the passing_over of one in the playing position is being passed over in this round; one
// named by a waiting registration will be passed over. The front registration is the other one
// in the passer's position. Where the page shows a machine's waiting order together with
// previews of the other machine of a pair, the positions are grouped differently and the passer
// may stand apart from the front registration; the machine's own order (ownWaitingPositions) is
// read then, as the terminal reads its own queue for the same sentence. Keyed by registration id.
export function soloPassStatesByRegistrationId(machines) {
  const allowing = new Map()
  const playing = new Map()
  const scheduled = new Map()
  for (const machine of machines || []) {
    const groups = [
      { isPlaying: true, registrations: machine.playing || [] },
      ...(machine.ownWaitingPositions || machine.waitingPositions || []).map((position) => ({
        isPlaying: false,
        registrations: position.registrations || []
      }))
    ]
    for (const group of groups) {
      for (const registration of group.registrations) {
        if (registration.allowsPassing && registration.registrationId) {
          allowing.set(registration.registrationId, registration)
        }
        const front = group.registrations.find((other) => other !== registration)
        if (!front) continue
        const found = { passerName: registration.displayId, frontName: front.displayId }
        for (const passedId of registration.passingOver || []) {
          const target = group.isPlaying ? playing : scheduled
          if (!target.has(passedId)) target.set(passedId, found)
        }
      }
    }
  }
  const states = {}
  for (const registrationId of allowing.keys()) {
    const current = playing.get(registrationId)
    const coming = scheduled.get(registrationId)
    states[registrationId] = current
      ? { kind: 'playing', ...current }
      : coming
        ? { kind: 'scheduled', ...coming }
        : { kind: 'enabled' }
  }
  return states
}

// The sentence of the registration's menu for its state, in three parts: the part about the
// coming or current passing is set in colour. [tellsHowToCancel] is false where the reader is not
// the player of that registration, such as another registration's detail on the status page.
// [fixedPair] words it for a registration of a fixed pair, whose choice is the pair's. The
// terminal also names its position menu for a fixed pair; the website has none.
export function soloPassStatusParts(state, { tellsHowToCancel = true, fixedPair = false } = {}) {
  if (!state) return null
  const subject = fixedPair ? '这组固定组合' : '这份登记'
  const cancel = tellsHowToCancel ? '可以在“更改游玩偏好”中取消。' : ''
  if (state.kind === 'scheduled' || state.kind === 'playing') {
    const front = `“${state.frontName}”`
    const passer = `“${state.passerName}”`
    return {
      before: `前方的${front}与后方的${passer}均允许他人加入，`,
      highlighted: state.kind === 'playing'
        ? `${passer}越过${subject}，正在与${front}共同游玩`
        : `${passer}将越过${subject}与${front}共同游玩`,
      after: state.kind === 'playing'
        ? `，${subject}的游玩轮次未推迟。${cancel}`
        : `，${subject}的游玩轮次不会推迟。${cancel}`
    }
  }
  return {
    before: `已允许后方的登记越过${subject}与前方的登记共同游玩。${cancel}`,
    highlighted: '',
    after: ''
  }
}
