const test = require('node:test')
const assert = require('node:assert/strict')

const {
  HELP_TEXT,
  absenceOperationSuccessMessage,
  changeAbsenceState,
  formatMachineChoice,
  formatMachineReplyHint,
  formatOwnQueue,
  formatOwnQueueActions,
  formatNotificationQueueStatus,
  machineCanAcceptRegistration,
  onlineRegistrationProfileCompletionNotice,
  formatQueue,
  formatQueueNotification,
  nicknameValidationError,
  parseGender,
  parseMachineChoice,
  parsePlayPreference,
  parsePreference,
  parseNotificationPreference,
  queueConfirmationContextFields,
} = require('../lib')

test('locks the Bot confirmation to the registration state shown before the reply', () => {
  const context = queueConfirmationContextFields('queue-1', {
    registration_id: 'registration-1',
    machine_id: 'B',
    position: 'WAITING',
    fixed_pair: true,
    fixed_pair_id: 'pair-1',
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    online_registration_pending_check_in: false,
  })

  assert.deepEqual(context, {
    expected_queue_id: 'queue-1',
    expected_registration_id: 'registration-1',
    expected_machine_id: 'B',
    expected_position: 'WAITING',
    expected_fixed_pair_id: 'pair-1',
    expected_absence_status: 'TEMPORARILY_AWAY',
    expected_temporary_away_skipped_turns: 2,
    expected_pending_check_in: false,
  })
})

test('explains the extra on-site step for legacy online-registration profiles', () => {
  const notice = onlineRegistrationProfileCompletionNotice(0)

  assert.match(notice, /先在终端补全资料/)
  assert.match(notice, /再点击“已到场”完成签到/)
  assert.equal(onlineRegistrationProfileCompletionNotice(1), null)
})

test('help text uses a message-safe profile menu', () => {
  assert.doesNotMatch(HELP_TEXT, /[<>]/)
  assert.match(HELP_TEXT, /修改资料/)
  assert.match(HELP_TEXT, /加入排队/)
  assert.match(HELP_TEXT, /排队通知/)
  assert.match(HELP_TEXT, /设置 QQ 后才能使用/)
})

test('does not present a wait estimate while the current QQ is temporarily away', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 2,
    estimated_wait_minutes: 18,
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    no_show_count: 1,
    last_no_show_action_was_defer: true,
  }])

  assert.match(text, /队列位置 A2/)
  assert.match(text, /暂时离开期间无法估算等待时间/)
  assert.doesNotMatch(text, /约 18 分钟后/)
  assert.match(text, /暂时离开·已轮空 2 次/)
  assert.match(text, /未到场记录 1 次/)
  assert.match(text, /未到场记录 1 次·上次处理：暂缓一次/)
})

test('shows move-to-end as the latest no-show handling result', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-2',
    profile_id: 'profile-2',
    qq_number: '87654321',
    display_id: '小林',
    machine_id: 'B',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 2,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /未到场记录 2 次/)
  assert.match(text, /上次处理：移至队尾/)
})

test('describes a zero-minute personal estimate as available soon', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-ready',
    profile_id: 'profile-ready',
    qq_number: '87654321',
    display_id: '即将游玩',
    machine_id: 'B',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 0,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /预计很快可以游玩/)
  assert.doesNotMatch(text, /不足 1 分钟/)
  assert.doesNotMatch(text, /约 0 分钟后/)
})

test('explains a missing estimate from an older personal response', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-without-estimate',
    profile_id: 'profile-without-estimate',
    qq_number: '87654321',
    display_id: '旧数据玩家',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /^你好，旧数据玩家。\n\n你位于队列位置 A1，暂时无法估算。/)
  assert.doesNotMatch(text, /undefined|约 .* 分钟后/)
})

test('shows business-hours state in personal queue output and notifications', () => {
  const player = {
    registration_id: 'registration-closing',
    profile_id: 'profile-closing',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 7,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }
  const personalSnapshot = {
    terminal: { online: true },
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: true,
      closing_soon: false,
      closing_grace: true,
      closes_at: null,
      registration_closes_at: Date.now() + 10 * 60_000,
    },
  }

  const text = formatOwnQueue([player], undefined, personalSnapshot)
  const closingSoonText = formatOwnQueue([player], undefined, {
    ...personalSnapshot,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 30 * 60_000,
      registration_closes_at: null,
    },
  })

  assert.match(text, /^你好，小雨。\n\n今日营业时间已结束，现有队列正在收尾。\n\n/)
  assert.match(text, /你位于队列位置 A1/)
  assert.match(closingSoonText, /^你好，小雨。\n\n将在 30 分钟内闭店，请留意后续队列安排。\n\n/)

  const offlineClosingSoonText = formatOwnQueue([player], undefined, {
    ...personalSnapshot,
    terminal: { online: false },
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 30 * 60_000,
      registration_closes_at: null,
    },
  })
  const offlineClosingGraceText = formatOwnQueue([player], undefined, {
    ...personalSnapshot,
    terminal: { online: false },
  })

  assert.doesNotMatch(offlineClosingSoonText, /将在 30 分钟内闭店/)
  assert.match(offlineClosingGraceText, /当前不在营业时间。/)
  assert.doesNotMatch(offlineClosingGraceText, /现有队列正在收尾/)
})

test('formats both machines without exposing private profile fields', () => {
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: false,
        stop_reason: 'NETWORK_DISCONNECTED',
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })
  assert.match(text, /^当前队列·终端在线/m)
  assert.doesNotMatch(text, /maimai Q/i)
  assert.match(text, /^当前队列·终端在线\n\n【入口侧·机台 A】/m)

  assert.match(text, /【入口侧·机台 A】/)
  assert.match(text, /游玩位置 A·空闲/)
  assert.match(text, /【墙侧·机台 B】/)
  assert.match(text, /停止使用·机台断网/)
  assert.match(text, /【入口侧·机台 A】\n\n游玩位置 A·空闲/)
  assert.doesNotMatch(text, /暂无等待登记/)
  assert.match(text, /游玩位置 A·空闲\n\n【墙侧·机台 B】/)
  assert.doesNotMatch(text, /qq_number|profile_id/)
})

test('labels test queue data in queue and personal status messages', () => {
  const queue = {
    queue_id: 'queue-test',
    captured_at: Date.now(),
    registration_open: true,
    test_data: true,
    terminal: { online: true },
    machines: {},
  }

  assert.match(formatQueue(queue), /^当前队列·终端在线\n\n当前数据是测试数据。$/)
  assert.match(
    formatOwnQueue([{
      registration_id: 'registration-1',
      profile_id: 'profile-1',
      display_id: '小雨',
      machine_id: 'A',
      position: 'WAITING',
      position_index: 1,
      online_registration_pending_check_in: true,
    }], queue),
    /\n\n当前数据是测试数据。\n\n/,
  )
})

test('keeps preserved registrations visible while a machine is stopped', () => {
  const registration = {
    registration_id: 'registration-1',
    display_id: '小雨',
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    no_show_count: 1,
  }
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: false,
        stop_reason: 'NOT_POWERED_ON',
        playing_started_at: null,
        playing: [],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 0,
          registrations: [registration],
        }],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })

  assert.match(text, /停止使用·机台未开机/)
  assert.match(text, /登记顺序已保留/)
  assert.match(
    text,
    /位置 A1·机台恢复使用后重新估算\n - 小雨 \(允许加入\)\n    - 暂时离开·已轮空 2 次\n    - 未到场记录 1 次/,
  )
  assert.match(text, /1 个等待位置·1 个登记/)
  assert.doesNotMatch(text, /约 0 分钟后/)
  assert.doesNotMatch(text, /小雨（|允许他人加入/)
})

test('shows fixed pairs and the complete machine stop reason', () => {
  const fixedRegistration = (id, name) => ({
    registration_id: id,
    display_id: name,
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: true,
    no_show_count: 0,
  })
  const machine = (id, overrides = {}) => ({
    id,
    name: `${id === 'A' ? '入口侧' : '墙侧'} · 机台 ${id}`,
    operational: true,
    stop_reason: null,
    stop_reason_detail: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: [],
    ...overrides,
  })
  const fixedPairText = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: machine('A', {
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 0,
          registrations: [
            fixedRegistration('registration-1', '小雨'),
            fixedRegistration('registration-2', '青空'),
          ],
        }],
      }),
      B: machine('B', {
        operational: false,
        stop_reason: 'OTHER',
        stop_reason_detail: '等待配件',
      }),
    },
  })

  assert.match(fixedPairText, /小雨 \(固定组合\)/)
  assert.match(fixedPairText, /青空 \(固定组合\)/)
  assert.match(fixedPairText, /停止使用·其他原因（等待配件）/)

  const maintenanceText = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: machine('A'),
      B: machine('B', {
        operational: false,
        stop_reason: 'MAINTENANCE',
      }),
    },
  })
  assert.match(maintenanceText, /停止使用·机台维护/)
})

test('does not show a stale personal estimate while the machine is stopped', () => {
  const player = {
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    online_registration_pending_check_in: true,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }
  const text = formatOwnQueue([player], {
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: false,
        stop_reason: 'NOT_POWERED_ON',
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })

  assert.match(text, /机台状态：入口侧·机台 A 已停止使用·机台未开机，登记顺序已保留/)
  assert.match(text, /机台停止使用期间，30 分钟签到计时暂停；恢复正常使用后会从头开始/)
  assert.doesNotMatch(text, /超过 30 分钟/)
  assert.doesNotMatch(text, /约 8 分钟后/)
})

test('keeps personal status complete without a public queue snapshot', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_name: '入口侧 · 机台 A',
    machine_operational: false,
    machine_stop_reason: 'OTHER',
    machine_stop_reason_detail: '等待配件',
    playing_started_at: null,
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    co_player_display_ids: ['青空'],
    preference: 'OPEN_TO_JOIN',
    fixed_pair: true,
    registration_type: 'PLAYER_PROFILE',
    last_played_at: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }], undefined, { terminal: { online: false } })

  assert.match(text, /^你好，小雨。\n\n终端暂时离线/)
  assert.match(text, /机台状态：入口侧·机台 A 已停止使用·其他原因（等待配件），登记顺序已保留。/)
  assert.match(text, /游玩偏好：与朋友共同游玩/)
  assert.match(text, /共同游玩：青空/)
  assert.doesNotMatch(text, /约 8 分钟后/)
  assert.doesNotMatch(text, /允许他人加入/)
})

test('uses the private playing timer when formatting a notification status', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_operational: true,
    playing_started_at: Date.now() - 5 * 60_000,
    position: 'PLAYING',
    position_index: null,
    estimated_wait_minutes: null,
    preference: 'SOLO',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /正在游玩位置 A，已游玩 [45] 分钟/)
  assert.match(text, /游玩偏好：单人游玩/)
})

test('describes a retained playing position as a location while its machine is stopped', () => {
  const player = {
    registration_id: 'registration-stopped-playing',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_name: '入口侧 · 机台 A',
    machine_operational: false,
    machine_stop_reason: 'NETWORK_DISCONNECTED',
    machine_stop_reason_detail: null,
    playing_started_at: Date.now() - 8 * 60_000,
    position: 'PLAYING',
    position_index: null,
    estimated_wait_minutes: null,
    preference: 'SOLO',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  const text = formatOwnQueue([player])

  assert.match(text, /位于游玩位置 A/)
  assert.match(text, /机台状态：入口侧·机台 A 已停止使用·机台断网，登记顺序已保留。/)
  assert.doesNotMatch(text, /正在游玩位置/)
  assert.doesNotMatch(text, /已游玩 \d+ 分钟/)
})

test('stops the personal playing timer while the terminal is offline', () => {
  const player = {
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'PLAYING',
    position_index: null,
    estimated_wait_minutes: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }
  const registration = {
    registration_id: 'registration-1',
    display_id: '小雨',
    preference: 'SOLO',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
  }
  const text = formatOwnQueue([player], {
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: false },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: true,
        stop_reason: null,
        playing_started_at: Date.now() - 5 * 60_000,
        playing: [registration],
        waiting_positions: [],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })

  assert.match(text, /^你好，小雨。\n\n终端暂时离线/)
  assert.match(text, /位于游玩位置 A/)
  assert.doesNotMatch(text, /正在游玩位置 A/)
  assert.doesNotMatch(text, /已游玩 \d+ 分钟/)
  assert.match(text, /游玩偏好：单人游玩/)
  assert.doesNotMatch(text, /\n\n - (修改游玩偏好|退出排队)/)
})

test('shows the computed business-hours state in the queue header', () => {
  const machines = {
    A: {
      id: 'A',
      name: '入口侧 · 机台 A',
      operational: true,
      stop_reason: null,
      playing_started_at: null,
      playing: [],
      waiting_positions: [],
    },
    B: {
      id: 'B',
      name: '墙侧 · 机台 B',
      operational: true,
      stop_reason: null,
      playing_started_at: null,
      playing: [],
      waiting_positions: [],
    },
  }
  const outside = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: true,
      closing_soon: false,
      closes_at: null,
    },
    terminal: { online: true },
    machines,
  })
  const naturalQueue = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: false,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: false,
      closes_at: Date.now() + 60_000,
    },
    terminal: { online: true },
    machines,
  })

  assert.match(outside, /^当前队列·终端在线·不在营业时间$/m)
  assert.match(naturalQueue, /^当前队列·终端在线·自然排队$/m)
})

test('shows both the thirty-minute closing warning and the closing grace period', () => {
  const emptyMachine = id => ({
    id,
    name: `${id === 'A' ? '入口侧' : '墙侧'} · 机台 ${id}`,
    operational: true,
    stop_reason: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: [],
  })
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: true,
      closing_soon: false,
      closing_grace: true,
      closes_at: null,
      registration_closes_at: Date.now() + 20 * 60_000,
    },
    terminal: { online: true },
    machines: { A: emptyMachine('A'), B: emptyMachine('B') },
  })
  const closingSoonText = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 30 * 60_000,
      registration_closes_at: null,
    },
    terminal: { online: true },
    machines: { A: emptyMachine('A'), B: emptyMachine('B') },
  })

  assert.match(text, /^当前队列·终端在线·不在营业时间$/m)
  assert.match(text, /今日营业时间已结束/)
  assert.match(text, /最迟保留 20 分钟/)
  assert.match(closingSoonText, /将在 30 分钟内闭店/)
  assert.match(closingSoonText, /请留意后续队列安排。/)
})

test('queue notifications append only the concise current position', () => {
  const status = formatNotificationQueueStatus([{
    registration_id: 'registration-checked-in',
    profile_id: 'profile-checked-in',
    qq_number: '12345678',
    display_id: '啊波呲',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 10,
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])
  const message = formatQueueNotification(
    {
      title: '左侧日框 · 机台 A · 线上登记签到状态已更新',
      detail: '“啊波呲”已在现场完成签到。',
    },
    status,
  )

  assert.equal(
    message,
    '【排队通知】\n\n左侧日框·机台 A·线上登记签到状态已更新\n“啊波呲”已在现场完成签到。\n\n现在，你位于队列位置 A1，约 10 分钟后可以游玩。',
  )
  assert.doesNotMatch(message, /\s·|·\s/)
  assert.doesNotMatch(message, /你好|游玩偏好|暂缓一次|退出排队/)
})

test('notification position summaries handle playing and unavailable estimates', () => {
  const base = {
    registration_id: 'registration-status',
    profile_id: 'profile-status',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'B',
    position_index: 2,
    estimated_wait_minutes: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  assert.equal(
    formatNotificationQueueStatus([{ ...base, position: 'WAITING' }]),
    '现在，你位于队列位置 B2，暂时无法估算。',
  )
  assert.equal(
    formatNotificationQueueStatus([{ ...base, position: 'PLAYING' }]),
    '现在，你正在游玩位置 B。',
  )
  assert.equal(
    formatNotificationQueueStatus([{
      ...base,
      position: 'WAITING',
      estimated_wait_minutes: 12,
      temporarily_away: true,
    }]),
    '现在，你位于队列位置 B2，暂时离开期间无法估算等待时间。',
  )
  assert.equal(
    formatNotificationQueueStatus([{
      ...base,
      position: 'WAITING',
      estimated_wait_minutes: 12,
      online_registration_pending_check_in: true,
    }]),
    '现在，你位于队列位置 B2，约 12 分钟后可以游玩。',
  )
})

test('does not keep advancing public timers or estimates while the terminal is offline', () => {
  const text = formatQueue({
    queue_id: 'queue-offline',
    captured_at: Date.now() - 5 * 60_000,
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 25 * 60_000,
      registration_closes_at: null,
    },
    terminal: { online: false },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: true,
        stop_reason: null,
        playing_started_at: Date.now() - 25 * 60_000,
        playing: [{
          registration_id: 'registration-playing',
          display_id: '小雨',
          preference: 'SOLO',
          deferred_once: false,
          temporarily_away: false,
          temporary_away_skipped_turns: 0,
          no_show_count: 0,
        }],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 9,
          registrations: [{
            registration_id: 'registration-waiting',
            display_id: '青空',
            preference: 'OPEN_TO_JOIN',
            deferred_once: false,
            temporarily_away: false,
            temporary_away_skipped_turns: 0,
            no_show_count: 0,
          }],
        }],
      },
    },
  })

  assert.match(text, /游玩位置 A·状态待更新/)
  assert.match(text, /位置 A1·状态待更新/)
  assert.doesNotMatch(text, /25 分钟|约 9 分钟后/)
  assert.doesNotMatch(text, /将在 30 分钟内闭店/)
})

test('shows the pending check-in state and only allows leaving the queue', () => {
  const player = {
    registration_id: 'registration-online',
    profile_id: 'profile-online',
    qq_number: '12345678',
    display_id: '糍粑',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 4,
    estimated_wait_minutes: 18,
    preference: 'OPEN_TO_JOIN',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: true,
    created_at: 1_000,
    online_check_in_started_at: 1_000,
  }

  const text = formatOwnQueue([player])

  assert.match(text, /^你好，糍粑。/)
  assert.match(text, /你位于队列位置 A4，约 18 分钟后可以游玩。/)
  assert.match(text, /请在创建登记后的 30 分钟内/)
  assert.match(text, /当前状态：线上登记·待签到。/)
  assert.match(text, /\n\n - 退出排队$/)
  assert.doesNotMatch(text, /暂缓一次|暂时离开|切换机台|修改游玩偏好/)
  assert.deepEqual(formatOwnQueueActions(player), ['退出排队'])

  const restartedText = formatOwnQueue([{
    ...player,
    online_check_in_started_at: 2_000,
  }])
  assert.match(restartedText, /机台恢复正常使用后，这份登记已重新获得 30 分钟签到时限/)
  assert.doesNotMatch(restartedText, /请在创建登记后的 30 分钟内/)
})

test('formats pending check-in registrations in the public queue', () => {
  const registration = {
    registration_id: 'registration-online',
    display_id: '糍粑',
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: false,
    no_show_count: 0,
    online_registration_pending_check_in: true,
  }
  const machine = id => ({
    id,
    name: `机台 ${id}`,
    operational: true,
    stop_reason: null,
    stop_reason_detail: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: id === 'A'
      ? [{ index: 1, estimated_wait_minutes: 9, registrations: [registration] }]
      : [],
  })
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: { A: machine('A'), B: machine('B') },
  })

  assert.match(text, /位置 A1·约 9 分钟后/)
  assert.match(text, /糍粑 \(允许加入\)\n    - 线上登记·待签到/)
})

test('formats common-play previews without counting them as real registrations', () => {
  const registration = {
    registration_id: 'registration-real',
    display_id: '戊',
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: false,
    no_show_count: 0,
    online_registration_pending_check_in: false,
  }
  const text = formatQueue({
    queue_id: 'queue-preview',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '左侧·机台 A',
        operational: true,
        stop_reason: null,
        stop_reason_detail: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 20,
          registrations: [registration],
          common_play_preview: {
            registration_id: 'registration-returning',
            display_id: '甲',
          },
        }],
      },
    },
  })

  assert.match(text, /1 个等待位置·1 个登记/)
  assert.match(text, / - 戊 \(允许加入\)\n - 甲 \(共同游玩预览\)/)
})

test('distinguishes a projected partner from real group members in personal status', () => {
  const player = {
    registration_id: 'registration-self',
    profile_id: 'profile-self',
    qq_number: '12345678',
    display_id: '戊',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 3,
    estimated_wait_minutes: 20,
    co_player_display_ids: ['乙', '甲'],
    common_play_preview_display_id: '甲',
    preference: 'OPEN_TO_JOIN',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
  }

  const text = formatOwnQueue([player])

  assert.match(text, /共同游玩：乙。/)
  assert.match(text, /预计与“甲”共同游玩。/)
  assert.doesNotMatch(text, /共同游玩：乙、甲/)
})

test('changes the personal menu with absence state and queue rules', () => {
  const player = {
    position: 'WAITING',
    deferred_once: false,
    temporarily_away: false,
    online_registration_pending_check_in: false,
  }
  assert.deepEqual(formatOwnQueueActions(player, {
    allow_defer_one_round: true,
    allow_temporary_leave: true,
  }), [
    '暂缓一次',
    '暂时离开',
    '切换机台',
    '修改游玩偏好',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions({ ...player, deferred_once: true }), [
    '取消暂缓一次',
    '切换机台',
    '修改游玩偏好',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions(player, {
    allow_defer_one_round: false,
    allow_temporary_leave: false,
  }), ['切换机台', '修改游玩偏好', '退出排队'])
  assert.deepEqual(formatOwnQueueActions(player, undefined, false), [])
  assert.deepEqual(formatOwnQueueActions({
    ...player,
    machine_operational: false,
  }), [])
})

test('cancels temporary leave for a fixed pair after the terminal snapshot confirms it', async () => {
  const player = {
    registration_id: 'registration-fixed-player',
    profile_id: 'profile-fixed-player',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: null,
    fixed_pair: true,
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
  }
  const responses = [
    {
      queue_id: 'queue-fixed',
      queue_rules: {
        allow_defer_one_round: true,
        allow_temporary_leave: true,
      },
      players: [player],
    },
    {
      queue_id: 'queue-fixed',
      players: [{
        ...player,
        temporarily_away: false,
        temporary_away_skipped_turns: 0,
      }],
    },
  ]
  const submitted = []
  const api = {
    getPlayers: async () => responses.shift() || responses[responses.length - 1],
    createQueueCommand: async (_qq, operation) => {
      submitted.push(operation)
      return {
        command_id: 'command-fixed',
        status: 'APPLIED',
        result_detail: '固定组合的两份登记已同时取消暂时离开。',
      }
    },
  }

  const text = await changeAbsenceState(
    api,
    { commandWaitSeconds: 3 },
    { platform: 'onebot', userId: '12345678', isDirect: true },
    'CANCEL_TEMPORARY_LEAVE',
  )

  assert.deepEqual(submitted, ['CANCEL_TEMPORARY_LEAVE'])
  assert.match(text, /固定组合的两份登记已同时取消暂时离开/)
  assert.match(text, /轮空次数均已清零/)
})

test('fixed-pair personal menu exposes the matching cancel action', () => {
  const actions = formatOwnQueueActions({
    position: 'WAITING',
    fixed_pair: true,
    deferred_once: false,
    temporarily_away: true,
    online_registration_pending_check_in: false,
  })

  assert.deepEqual(actions, [
    '取消暂时离开',
    '切换机台',
    '修改游玩偏好',
    '退出排队',
  ])
})

test('explains all fixed-pair absence operations as whole-group changes', () => {
  assert.match(
    absenceOperationSuccessMessage('DEFER_ONE_ROUND', true),
    /两份登记已同时暂缓一次.*跳过整组/,
  )
  assert.match(
    absenceOperationSuccessMessage('CANCEL_DEFER_ONE_ROUND', true),
    /两份登记已同时取消暂缓一次/,
  )
  assert.match(
    absenceOperationSuccessMessage('TEMPORARILY_LEAVE', true),
    /两份登记已同时设为暂时离开.*忽略整组.*整组将退出排队/s,
  )
  assert.match(
    absenceOperationSuccessMessage('CANCEL_TEMPORARY_LEAVE', true),
    /两份登记已同时取消暂时离开.*轮空次数均已清零/,
  )
  assert.doesNotMatch(
    absenceOperationSuccessMessage('CANCEL_TEMPORARY_LEAVE', false),
    /固定组合|两份登记|整组/,
  )
})

test('accepts concise machine letters, full names, and unique remarks', () => {
  const machines = [
    { id: 'A', name: '左侧 · 机台 A' },
    { id: 'B', name: '右侧 · 机台 B' },
  ]
  assert.equal(parseMachineChoice('A', machines), machines[0])
  assert.equal(parseMachineChoice('b', machines), machines[1])
  assert.equal(parseMachineChoice('  a  ', machines), machines[0])
  assert.equal(parseMachineChoice('\t右侧\n', machines), machines[1])
  assert.equal(parseMachineChoice('机台 A', machines), machines[0])
  assert.equal(parseMachineChoice('右侧·机台B', machines), machines[1])
  assert.equal(parseMachineChoice('左侧', machines), machines[0])
  assert.equal(parseMachineChoice('右侧', machines), machines[1])
  assert.equal(parseMachineChoice('左边', machines), null)
  assert.equal(parseMachineChoice('入口', [
    { id: 'A', name: '入口 · 机台 A' },
    { id: 'B', name: '入口 · 机台 B' },
  ]), null)
  assert.equal(parsePlayPreference('单人游玩'), 'SOLO')
  assert.equal(parsePlayPreference('允许他人加入'), 'OPEN_TO_JOIN')
  assert.equal(parsePlayPreference('每次询问'), null)
})

test('presents machine choices with the short letter first', () => {
  const machines = [
    { id: 'A', name: '左侧日框 · 机台 A', new_registration_estimated_wait_minutes: 12 },
    { id: 'B', name: '机台 B', new_registration_estimated_wait_minutes: 0 },
  ]
  assert.equal(formatMachineChoice(machines[0]), 'A（左侧日框，约 12 分钟后）')
  assert.equal(formatMachineChoice(machines[1]), 'B（预计很快可以游玩）')
  assert.equal(
    formatMachineChoice({ id: 'C', name: '机台 C' }),
    'C（暂时无法估算）',
  )
  assert.equal(
    formatMachineReplyHint(machines),
    '回复 A、B，也可以回复括号中的机台备注。',
  )
})

test('does not offer stopped or full machines for a new registration', () => {
  const machine = {
    id: 'A',
    name: '左侧 · 机台 A',
    operational: true,
    playing: [],
    waiting_positions: [],
    registration_count: 19,
  }

  assert.equal(machineCanAcceptRegistration(machine), true)
  assert.equal(machineCanAcceptRegistration({ ...machine, registration_count: 20 }), false)
  assert.equal(machineCanAcceptRegistration({ ...machine, operational: false }), false)
})

test('parses only supported profile values', () => {
  assert.equal(parseGender('女'), 'FEMALE')
  assert.equal(parseGender('未知'), null)
  assert.equal(parsePreference('允许他人加入'), 'OPEN_TO_JOIN')
  assert.equal(parsePreference('固定组合'), null)
  assert.equal(parseNotificationPreference('开启'), true)
  assert.equal(parseNotificationPreference('关闭'), false)
  assert.equal(parseNotificationPreference('稍后'), null)
})

test('rejects unsafe or oversized profile nicknames', () => {
  assert.equal(nicknameValidationError('小雨'), null)
  assert.equal(nicknameValidationError('  '), '昵称应为 1 至 18 个字符。')
  assert.equal(nicknameValidationError('第一行\n第二行'), '昵称不能包含换行或控制字符。')
  assert.equal(nicknameValidationError('一二三四五六七八九十一二三四五六七八九'), '昵称应为 1 至 18 个字符。')
})
