'use strict';

const fastify = require('fastify')({ logger: true });
const crypto = require('crypto');

// In-memory store
const messages = [];

// --- Twilio API surface ---

// POST /2010-04-01/Accounts/:accountSid/Messages.json — Send a message
fastify.post('/2010-04-01/Accounts/:accountSid/Messages.json', async (request, reply) => {
  // Twilio sends form-encoded, but we accept JSON too
  const body = request.body || {};
  const to = body.To || body.to;
  const from = body.From || body.from;
  const msgBody = body.Body || body.body;

  if (!to || !msgBody) {
    return reply.status(400).send({
      code: 21211,
      message: "'To' and 'Body' are required",
      status: 400,
    });
  }

  const message = {
    sid: 'SM' + crypto.randomUUID().replace(/-/g, ''),
    account_sid: request.params.accountSid,
    to,
    from: from || '+15005550006',
    body: msgBody,
    status: 'sent',
    direction: 'outbound-api',
    date_created: new Date().toISOString(),
    date_sent: new Date().toISOString(),
    price: null,
    price_unit: 'USD',
    uri: `/2010-04-01/Accounts/${request.params.accountSid}/Messages/${this?.sid}.json`,
  };

  // Set the URI now that we have the SID
  message.uri = `/2010-04-01/Accounts/${request.params.accountSid}/Messages/${message.sid}.json`;

  messages.push(message);
  fastify.log.info({ to, body: msgBody }, 'SMS captured');

  return reply.status(201).send(message);
});

// GET /2010-04-01/Accounts/:accountSid/Messages/:sid.json — Get message by SID
fastify.get('/2010-04-01/Accounts/:accountSid/Messages/:sid.json', async (request, reply) => {
  const message = messages.find(m => m.sid === request.params.sid);
  if (!message) {
    return reply.status(404).send({ code: 20404, message: 'Message not found', status: 404 });
  }
  return message;
});

// GET /2010-04-01/Accounts/:accountSid/Messages.json — List messages
fastify.get('/2010-04-01/Accounts/:accountSid/Messages.json', async (request) => {
  const { To, From } = request.query;
  let filtered = messages.filter(m => m.account_sid === request.params.accountSid);
  if (To) filtered = filtered.filter(m => m.to === To);
  if (From) filtered = filtered.filter(m => m.from === From);
  return { messages: filtered };
});

// --- Test query API (custom, for E2E test assertions) ---

// GET /api/messages — List all captured messages, optionally filter by recipient
fastify.get('/api/messages', async (request) => {
  const { to } = request.query;
  if (to) {
    return messages.filter(m => m.to === to);
  }
  return messages;
});

// GET /api/messages/latest — Get the most recent message (optionally by recipient)
fastify.get('/api/messages/latest', async (request, reply) => {
  const { to } = request.query;
  const filtered = to ? messages.filter(m => m.to === to) : messages;
  if (filtered.length === 0) {
    return reply.status(404).send({ message: 'No messages found' });
  }
  return filtered[filtered.length - 1];
});

// DELETE /api/messages — Clear all captured messages
fastify.delete('/api/messages', async () => {
  const count = messages.length;
  messages.length = 0;
  return { cleared: count };
});

// GET /health — Health check
fastify.get('/health', async () => {
  return { status: 'ok', service: 'smithr-mock-sms', messages: messages.length };
});

// Support form-encoded bodies (Twilio default)
fastify.addContentTypeParser(
  'application/x-www-form-urlencoded',
  { parseAs: 'string' },
  (req, body, done) => {
    try {
      const parsed = Object.fromEntries(new URLSearchParams(body));
      done(null, parsed);
    } catch (err) {
      done(err);
    }
  }
);

// Start
const start = async () => {
  const port = process.env.PORT || 3200;
  const host = process.env.HOST || '0.0.0.0';
  await fastify.listen({ port, host });
};

start();
