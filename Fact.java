/**
 * Simple 10-20 lines test program for the initial JikesRVM/JDWP Google SOC
 * work.
 */
public class Fact {

  /** The main method.*/
  public static void main(String[] args) {
    System.out.println("fact(4) = " + fact(4));
  }

  /**
   * compute the factorial number
   *
   * @param n the input number.
   */
  private static int fact(int n) {
    if ( n <= 0 ) {
      return 1;
    } else {
      return n * fact( n - 1 );
    }
  }
}
