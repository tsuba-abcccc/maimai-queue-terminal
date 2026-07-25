const test = require('node:test')
const assert = require('node:assert/strict')

const {
  QueueApi,
  apiBaseValidationError,
  isOnlyBotMention,
  profileUpdateErrorMessage,
  requireQqSession,
  resolveProfileCommandInput,
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
