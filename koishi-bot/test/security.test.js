const test = require('node:test')
const assert = require('node:assert/strict')

const {
  QueueApi,
  apiBaseValidationError,
  isOnlyBotMention,
  normalizedReportedVersion,
  profileUpdateErrorMessage,
  publicQueueUrlValidationError,
  requireQqSession,
  resolveProfileCommandInput,
  resolveQueueCommandInput,
} = require('../lib')

test('opens the menu only for a standalone mention of the current bot', () => {
  const mention = { type: 'at', attrs: { id: '10000' } }
  const whitespace = { type: 'text', attrs: { content: '  ' } }
  const command = { type: 'text', attrs: { content: ' 查看队列' } }

  assert.equal(isOnlyBotMention({
    platform: 'onebot',
    selfId: '10000',
    elements: [mention, whitespace],
  }), true)
  assert.equal(isOnlyBotMention({
    platform: 'onebot',
    selfId: '10000',
    elements: [mention, command],
  }), false)
  assert.equal(isOnlyBotMention({
    platform: 'onebot',
    selfId: '20000',
    elements: [mention],
  }), false)
})

test('requires personal commands to run in a direct OneBot session', () => {
  assert.equal(requireQqSession({
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
  }), '12345678')

  assert.throws(() => requireQqSession({
    platform: 'onebot',
    userId: '12345678',
    isDirect: false,
  }), /请私聊机器人/)
  assert.throws(() => requireQqSession({
    platform: 'discord',
    userId: '12345678',
    isDirect: true,
  }), /只能由 OneBot QQ 用户使用/)
})

test('uses an inline profile value without starting an input session', async () => {
  const session = {
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    async send() { assert.fail('不应发送输入提示') },
    async prompt() { assert.fail('不应等待后续输入') },
  }

  assert.equal(
    await resolveProfileCommandInput(session, '  新昵称  ', '请输入新的昵称。'),
    '新昵称',
  )
})

test('waits for the next direct message when a profile value is omitted', async () => {
  const sent = []
  const session = {
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    async send(message) { sent.push(message) },
    async prompt(timeout) {
      assert.equal(timeout, 60_000)
      return '  不愿透露  '
    },
  }

  assert.equal(
    await resolveProfileCommandInput(session, undefined, '请输入性别。'),
    '不愿透露',
  )
  assert.match(sent[0], /请输入性别/)
  assert.match(sent[0], /发送“取消”/)
})

test('handles cancellation and timeout without submitting a profile update', async () => {
  const session = reply => ({
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    async send() {},
    async prompt() { return reply },
  })

  await assert.rejects(
    resolveProfileCommandInput(session('取消'), undefined, '请输入新的昵称。'),
    /已取消这次修改/,
  )
  await assert.rejects(
    resolveProfileCommandInput(session(''), undefined, '请输入新的昵称。'),
    /这次修改没有提交/,
  )
})

test('submits profile updates as JSON and exposes the server rejection reason', async () => {
  const requests = []
  const http = async (...args) => {
    requests.push(args)
    return { status: 400, data: { error: '请求内容不符合资料修改规则' } }
  }
  const api = new QueueApi({ http }, {
    apiBase: 'https://queue.example.test',
    botToken: 'test-token',
    notificationEnabled: false,
    notificationIntervalSeconds: 5,
    commandWaitSeconds: 15,
  })

  await assert.rejects(
    api.updateProfile(
      '00000000-0000-0000-0000-000000000001',
      '12345678',
      { gender: 'FEMALE' },
    ),
    /请求内容不符合资料修改规则/,
  )
  assert.equal(requests[0][0], 'PATCH')
  assert.equal(requests[0][2].data.actor_qq, '12345678')
  assert.equal(requests[0][2].data.gender, 'FEMALE')
})

test('submits queue operations with a unique request id and no undefined fields', async () => {
  const requests = []
  const http = async (...args) => {
    requests.push(args)
    return {
      status: 202,
      data: {
        command_id: '00000000-0000-0000-0000-000000000401',
        status: 'PENDING',
        result_detail: null,
      },
    }
  }
  const api = new QueueApi({ http }, {
    apiBase: 'https://queue.example.test',
    botToken: 'test-token',
    notificationEnabled: false,
    notificationIntervalSeconds: 5,
    commandWaitSeconds: 15,
  })

  await api.createQueueCommand('12345678', 'JOIN_QUEUE', {
    machine_id: 'A',
    preference: undefined,
  })

  const [method, url, options] = requests[0]
  assert.equal(method, 'POST')
  assert.equal(url, 'https://queue.example.test/api/queue-bot/queue-commands')
  assert.match(options.data.request_id, /^[0-9a-f-]{36}$/)
  assert.equal(options.data.actor_qq, '12345678')
  assert.equal(options.data.operation, 'JOIN_QUEUE')
  assert.equal(options.data.machine_id, 'A')
  assert.equal('preference' in options.data, false)
  assert.equal(options.headers.Authorization, 'Bearer test-token')
})

test('retries queue confirmation without new fields only when an older server rejects them', async () => {
  const requests = []
  const http = async (...args) => {
    requests.push(args)
    if (requests.length === 1) {
      return { status: 400, data: { error: '请求包含不支持的排队操作字段' } }
    }
    return {
      status: 202,
      data: {
        command_id: '00000000-0000-0000-0000-000000000402',
        status: 'PENDING',
        result_detail: null,
      },
    }
  }
  const api = new QueueApi({ http }, {
    apiBase: 'https://queue.example.test',
    botToken: 'test-token',
    notificationEnabled: false,
    notificationIntervalSeconds: 5,
    commandWaitSeconds: 15,
  })

  await api.createQueueCommand('12345678', 'TRANSFER_MACHINE', {
    target_machine_id: 'B',
    expected_queue_id: '00000000-0000-0000-0000-000000000001',
    expected_registration_id: '1'.repeat(24),
    expected_machine_id: 'A',
    expected_position: 'WAITING',
    expected_fixed_pair_id: null,
    expected_absence_status: 'NONE',
    expected_temporary_away_skipped_turns: 0,
    expected_pending_check_in: false,
    expected_machine_configuration_revision: 7,
    expected_machine_stable_id: '2'.repeat(32),
    expected_target_machine_stable_id: '3'.repeat(32),
  })

  assert.equal(requests.length, 2)
  assert.equal(requests[1][2].data.request_id, requests[0][2].data.request_id)
  assert.equal('expected_machine_stable_id' in requests[1][2].data, false)
  assert.equal('expected_target_machine_stable_id' in requests[1][2].data, false)
  assert.equal('expected_machine_configuration_revision' in requests[1][2].data, false)
  assert.equal(requests[1][2].data.expected_registration_id, '1'.repeat(24))
})

test('queue input uses a separate paragraph and supports cancellation', async () => {
  const sent = []
  const session = reply => ({
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    async send(message) { sent.push(message) },
    async prompt(timeout) {
      assert.equal(timeout, 60_000)
      return reply
    },
  })

  assert.equal(await resolveQueueCommandInput(
    session('A'),
    '请选择机台：\n\n - 机台 A',
  ), 'A')
  assert.match(sent[0], /机台 A\n\n请在 60 秒内回复/)
  await assert.rejects(
    resolveQueueCommandInput(session('取消'), '请选择机台。'),
    /已取消这次操作/,
  )
})

test('formats profile update errors without leaking an HTML response', () => {
  assert.equal(
    profileUpdateErrorMessage('{"error":"昵称已经存在"}', 409),
    '昵称已经存在',
  )
  assert.equal(
    profileUpdateErrorMessage('<html>Bad Request</html>', 400),
    '服务器未接受这次资料修改（HTTP 400）。',
  )
})

test('requires HTTPS except for explicit loopback API addresses', () => {
  assert.equal(apiBaseValidationError('https://queue.example.test'), null)
  assert.equal(apiBaseValidationError('http://localhost:8080'), null)
  assert.equal(apiBaseValidationError('http://127.0.0.1:8080'), null)
  assert.equal(apiBaseValidationError('http://[::1]:8080'), null)
  assert.match(apiBaseValidationError('http://queue.example.test'), /必须使用 HTTPS/)
  assert.match(apiBaseValidationError('ftp://queue.example.test'), /必须使用 HTTPS/)
})

test('rejects ambiguous API base URLs and embedded credentials', () => {
  assert.match(apiBaseValidationError('not a url'), /有效的服务器地址/)
  assert.match(apiBaseValidationError('https://user:secret@queue.example.test'), /用户名或密码/)
  assert.match(apiBaseValidationError('https://queue.example.test/api'), /只填写站点根地址/)
  assert.match(apiBaseValidationError('https://queue.example.test?token=secret'), /只填写站点根地址/)
})

test('uses the public website origin independently from the API origin', async () => {
  const requested = []
  const api = new QueueApi({
    http: {
      async get(url) {
        requested.push(url)
        return { version: '0.10.1' }
      },
    },
  }, {
    apiBase: 'https://api.example.test',
    publicQueueUrl: 'https://queue.example.test',
    botToken: 'test-token',
    notificationEnabled: false,
    notificationIntervalSeconds: 5,
    commandWaitSeconds: 15,
  })

  assert.equal(api.publicUrl('/queue-status'), 'https://queue.example.test/queue-status')
  assert.equal(await api.getWebsiteVersion(), '0.10.1')
  assert.deepEqual(requested, ['https://queue.example.test/queue-client-version.json'])
  assert.equal(api.url('/api/queue-status'), 'https://api.example.test/api/queue-status')
})

test('validates the optional public website root independently', () => {
  assert.equal(publicQueueUrlValidationError(), null)
  assert.equal(publicQueueUrlValidationError('https://queue.example.test'), null)
  assert.equal(publicQueueUrlValidationError('http://localhost:4173'), null)
  assert.match(publicQueueUrlValidationError('http://queue.example.test'), /必须使用 HTTPS/)
  assert.match(publicQueueUrlValidationError('https://queue.example.test/queue-status'), /只填写网站根地址/)
  assert.match(publicQueueUrlValidationError('https://user:secret@queue.example.test'), /用户名或密码/)
})

test('validates reported versions with the same semantic-version rules as the server', () => {
  assert.equal(normalizedReportedVersion('v0.10.1'), '0.10.1')
  assert.equal(normalizedReportedVersion('1.0.0-rc.1+build.2'), '1.0.0-rc.1+build.2')
  assert.equal(normalizedReportedVersion('1.0.0-01'), undefined)
  assert.equal(normalizedReportedVersion('1.0'), undefined)
})
