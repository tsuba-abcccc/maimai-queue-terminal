const test = require('node:test')
const assert = require('node:assert/strict')

const {
  HELP_TEXT,
  formatOwnQueue,
  formatQueue,
  formatQueueNotification,
  nicknameValidationError,
  parseGender,
  parsePreference,
  parseNotificationPreference,
} = require('../lib')

test('help text uses a message-safe profile menu', () => {
  assert.doesNotMatch(HELP_TEXT, /[<>]/)
  assert.match(HELP_TEXT, /修改资料/)
  assert.match(HELP_TEXT, /排队通知/)
  assert.match(HELP_TEXT, /设置 QQ 后才能使用/)
})

test('formats waiting estimate and absence state for the current QQ', () => {
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
  assert.match(text, /约 18 分钟后/)
  assert.match(text, /暂时离开·已轮空 2 次/)
  assert.match(text, /未到场记录 1 次/)
  assert.match(text, /未到场记录 1 次·上次处理：暂缓一轮/)
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

test('describes a zero-minute personal estimate as ready now', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-ready',
    profile_id: 'profile-ready',
    qq_number: '87654321',
    display_id: '即将上机',
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

  assert.match(text, /预计现在可以游玩/)
  assert.doesNotMatch(text, /约 0 分钟后/)
})

test('omits the estimate when an older personal response has no estimate field', () => {
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

  assert.match(text, /旧数据玩家：位于队列位置 A1/)
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

  assert.match(text, /^今日营业时间已结束，现有队列正在收尾。\n\n/)
  assert.match(text, /小雨：位于队列位置 A1/)
  assert.match(closingSoonText, /^将在 30 分钟内闭店，请留意后续队列安排。\n\n/)
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
    /位置 A1\n - 小雨 \(允许加入\)\n    - 暂时离开·已轮空 2 次\n    - 未到场记录 1 次/,
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

  assert.match(text, /机台状态：停止使用·机台未开机，登记顺序已保留/)
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

  assert.match(text, /^终端暂时离线/)
  assert.match(text, /所在机台：入口侧·机台 A/)
  assert.match(text, /机台状态：停止使用·其他原因（等待配件），登记顺序已保留。/)
  assert.match(text, /本次偏好：与朋友共同游玩/)
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

  assert.match(text, /正在游玩位置 A·已游玩 [45] 分钟/)
  assert.match(text, /本次偏好：单人游玩/)
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
  assert.match(text, /机台状态：停止使用·机台断网，登记顺序已保留。/)
  assert.doesNotMatch(text, /正在游玩位置/)
  assert.doesNotMatch(text, /已游玩 \d+ 分钟/)
})

test('adds current preference, elapsed time, and stale warning to own status', () => {
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

  assert.match(text, /^终端暂时离线/)
  assert.match(text, /正在游玩位置 A·已游玩 [45] 分钟/)
  assert.match(text, /本次偏好：单人游玩/)
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

test('formats queue notifications with the canonical heading and compact middle dots', () => {
  const message = formatQueueNotification(
    {
      title: '机台 A · 游玩位置已更新',
      detail: '小雨 · 已进入游玩位置。',
    },
    '\n\n未到场记录 1 次 · 上次处理：移至队尾',
  )

  assert.equal(
    message,
    '【排队通知】\n\n机台 A·游玩位置已更新\n小雨·已进入游玩位置。\n\n未到场记录 1 次·上次处理：移至队尾',
  )
  assert.doesNotMatch(message, /\s·|·\s/)
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
