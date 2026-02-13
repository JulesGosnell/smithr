import { View, Text, TouchableOpacity, StyleSheet } from 'react-native'
import { useAuth } from '../../lib/auth-context'

export default function ProfileScreen() {
  const { user, logout } = useAuth()

  return (
    <View style={styles.container} testID="screen-profile">
      <View style={styles.card}>
        <Text style={styles.label}>Name</Text>
        <Text style={styles.value}>{user?.name}</Text>

        <Text style={styles.label}>Email</Text>
        <Text style={styles.value}>{user?.email}</Text>
      </View>

      <TouchableOpacity
        testID="button-sign-out"
        style={styles.button}
        onPress={logout}
      >
        <Text style={styles.buttonText}>Sign Out</Text>
      </TouchableOpacity>
    </View>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 24, backgroundColor: '#fff' },
  card: {
    backgroundColor: '#f8fafc',
    borderRadius: 12,
    padding: 20,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: '#e2e8f0',
  },
  label: { fontSize: 13, color: '#64748b', marginTop: 12 },
  value: { fontSize: 17, color: '#1e293b', fontWeight: '500', marginTop: 2 },
  button: {
    backgroundColor: '#dc2626',
    borderRadius: 8,
    padding: 16,
    alignItems: 'center',
  },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
})
