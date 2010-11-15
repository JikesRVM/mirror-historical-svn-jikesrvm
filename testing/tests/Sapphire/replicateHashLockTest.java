class replicateHashLockTest {
        public static void main(String[] args) {
                Object o = new Object();
                System.gc();
                int hash = o.hashCode();
                synchronized(o) {
                        System.out.println(hash);
                }
        }
}
