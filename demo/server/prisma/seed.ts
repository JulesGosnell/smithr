import { PrismaClient } from '@prisma/client'
import bcrypt from 'bcryptjs'

const prisma = new PrismaClient()

async function main() {
  const password = await bcrypt.hash('password123', 10)

  await prisma.user.upsert({
    where: { email: 'demo@smithr.dev' },
    update: {},
    create: {
      email: 'demo@smithr.dev',
      password,
      name: 'Demo User',
    },
  })

  await prisma.user.upsert({
    where: { email: 'test@smithr.dev' },
    update: {},
    create: {
      email: 'test@smithr.dev',
      password,
      name: 'Test User',
    },
  })

  console.log('Seeded 2 demo users: demo@smithr.dev, test@smithr.dev (password: password123)')
}

main()
  .catch(console.error)
  .finally(() => prisma.$disconnect())
