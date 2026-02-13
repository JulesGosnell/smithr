import { View, Text, StyleSheet } from 'react-native'
import { useAuth } from '../../lib/auth-context'

export default function HomeScreen() {
  const { user } = useAuth()

  return (
    <View style={styles.container} testID="screen-home">
      <Text testID="text-welcome" style={styles.greeting}>Welcome, {user?.name || 'User'}</Text>
      <Text style={styles.info}>
        This is the Smithr demo app. It exists to exercise the infrastructure:
        phone pools, shared servers, and CI pipelines.
      </Text>
    </View>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#fff' },
  greeting: { fontSize: 24, fontWeight: 'bold', marginBottom: 16, color: '#1e293b' },
  info: { fontSize: 15, color: '#64748b', lineHeight: 22 },
})
