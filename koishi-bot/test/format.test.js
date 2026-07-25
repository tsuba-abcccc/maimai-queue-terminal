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

  assert.match(text, /机台状态：停止使用，登记顺序已保留/)
  assert.doesNotMatch(text, /约 8 分钟后/)
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

test('shows the closing grace period without restoring the old pre-closing warning', () => {
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

  assert.match(text, /^当前队列·终端在线·不在营业时间$/m)
  assert.match(text, /今日营业时间已结束/)
  assert.match(text, /最迟保留 20 分钟/)
  assert.doesNotMatch(text, /距离闭店不足 15 分钟/)
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
