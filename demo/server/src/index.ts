import Fastify from 'fastify'
import cors from '@fastify/cors'
import { PrismaClient } from '@prisma/client'
import bcrypt from 'bcryptjs'
import jwt from 'jsonwebtoken'

const prisma = new PrismaClient()
const JWT_SECRET = process.env.JWT_SECRET || 'smithr-demo-jwt-secret'

async function main() {
  const fastify = Fastify({ logger: true })

  await fastify.register(cors, { origin: true })

  // --- Health ---

  fastify.get('/health', async () => ({
    status: 'ok',
    timestamp: new Date().toISOString(),
  }))

  fastify.get('/ready', async (_req, reply) => {
    try {
      await prisma.$queryRaw`SELECT 1`
      return { status: 'ready', database: true }
    } catch {
      reply.status(503)
      return { status: 'not_ready', database: false }
    }
  })

  // --- Auth ---

  fastify.post<{ Body: { email: string; password: string } }>(
    '/api/auth/login',
    async (req, reply) => {
      const { email, password } = req.body

      if (!email || !password) {
        reply.status(400)
        return { error: 'Email and password are required' }
      }

      const user = await prisma.user.findUnique({ where: { email } })
      if (!user) {
        reply.status(401)
        return { error: 'Invalid credentials' }
      }

      const valid = await bcrypt.compare(password, user.password)
      if (!valid) {
        reply.status(401)
        return { error: 'Invalid credentials' }
      }

      const token = jwt.sign(
        { userId: user.id, email: user.email },
        JWT_SECRET,
        { expiresIn: '24h' }
      )

      return {
        user: { id: user.id, email: user.email, name: user.name },
        token,
      }
    }
  )

  fastify.get('/api/auth/profile', async (req, reply) => {
    const auth = req.headers.authorization
    if (!auth?.startsWith('Bearer ')) {
      reply.status(401)
      return { error: 'No token provided' }
    }

    try {
      const payload = jwt.verify(auth.slice(7), JWT_SECRET) as {
        userId: string
      }
      const user = await prisma.user.findUnique({
        where: { id: payload.userId },
      })
      if (!user) {
        reply.status(401)
        return { error: 'User not found' }
      }
      return { user: { id: user.id, email: user.email, name: user.name } }
    } catch {
      reply.status(401)
      return { error: 'Invalid token' }
    }
  })

  // --- Start ---

  const port = parseInt(process.env.PORT || '3001', 10)
  const host = process.env.HOST || '0.0.0.0'

  await fastify.listen({ port, host })
  console.log(`Smithr Demo API listening on http://${host}:${port}`)
}

main()
