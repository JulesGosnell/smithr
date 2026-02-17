'use strict';

const fastify = require('fastify')({ logger: true });
const crypto = require('crypto');

// In-memory store
const emails = [];

// --- Resend API surface ---

// POST /emails — Send an email (Resend-compatible)
fastify.post('/emails', async (request, reply) => {
  const { from, to, subject, html, text, reply_to, cc, bcc, tags } = request.body || {};

  if (!from || !to) {
    return reply.status(422).send({ message: 'from and to are required' });
  }

  const toList = Array.isArray(to) ? to : [to];

  const email = {
    id: crypto.randomUUID(),
    from,
    to: toList,
    subject: subject || '',
    html: html || '',
    text: text || '',
    reply_to: reply_to || null,
    cc: cc || null,
    bcc: bcc || null,
    tags: tags || null,
    created_at: new Date().toISOString(),
    last_event: 'delivered',
  };

  emails.push(email);
  fastify.log.info({ to: toList, subject }, 'Email captured');

  return reply.status(200).send({ id: email.id });
});

// GET /emails/:id — Get email by ID (Resend-compatible)
fastify.get('/emails/:id', async (request, reply) => {
  const email = emails.find(e => e.id === request.params.id);
  if (!email) {
    return reply.status(404).send({ message: 'Email not found' });
  }
  return email;
});

// --- Test query API (custom, for E2E test assertions) ---

// GET /api/emails — List all captured emails, optionally filter by recipient
fastify.get('/api/emails', async (request) => {
  const { to } = request.query;
  if (to) {
    return emails.filter(e => e.to.includes(to));
  }
  return emails;
});

// GET /api/emails/latest — Get the most recent email (optionally by recipient)
fastify.get('/api/emails/latest', async (request, reply) => {
  const { to } = request.query;
  const filtered = to ? emails.filter(e => e.to.includes(to)) : emails;
  if (filtered.length === 0) {
    return reply.status(404).send({ message: 'No emails found' });
  }
  return filtered[filtered.length - 1];
});

// DELETE /api/emails — Clear all captured emails
fastify.delete('/api/emails', async () => {
  const count = emails.length;
  emails.length = 0;
  return { cleared: count };
});

// GET /health — Health check
fastify.get('/health', async () => {
  return { status: 'ok', service: 'smithr-mock-email', emails: emails.length };
});

// Start
const start = async () => {
  const port = process.env.PORT || 3100;
  const host = process.env.HOST || '0.0.0.0';
  await fastify.listen({ port, host });
};

start();
