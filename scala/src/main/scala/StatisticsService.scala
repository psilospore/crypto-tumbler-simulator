//Store stats and make recommendations on how much to deposit. Could be used for sliding values in the mixer.
//TODO if I choose to implement this do it in memory. Leave a TODO for persisting
class StatisticsService {
  def recordDepositFromUser(): Unit  = ???
  def recordDepositToAddress(): Unit = ???
  def stats                          = ???
}
