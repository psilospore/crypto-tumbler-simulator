# Jobcoin Tumbler

A simple tumbler that runs on Jobcoin.
Charges a randomized fee of up to 3%.

## Developer Notes

I've attempted to explain any non-common patterns, or usages of libraries outside of the provided libraries.
Such as my comment on extension methods or on Cats.

I also had to cut myself off at some point.
There are explanations of things I would do documented in the readme here
and in the codebase.

### Run
`sbt run`

### Test
`sbt test`

## Mixing

There's several different angles for vulnerabilities on mixing.
I will attempt to document some attacks, safety mechanisms I've added, and unaddressed
vulnerabilities.

Feel free to skip this section since it is a bit long.
However it does explain the following section and why I made some
of those decisions. 

### Examining the ledger

One vulnerability is to just check all transactions made for near that amount.
If the fee is static I can calculate the exact amount to search for.
The description says at least 1 address so I can't just require multiple addresses.
For that reason I give a warning if there's less than 4 addresses.

I also randomize the fee. Although if there's 1 address it's still easy to track.

If I also just process one transaction at a time. You could group transactions in a time
period to associate it to the originator.

If jobcoin is unpopular and there's very few transactions it becomes really easy to figure
out where money is being sent. In the worst case only 1 real person uses jobcoin all of that person's
transactions are visible.

### Vulnerabilities in jobcoin itself

Seems like there's no security. TODO describe more.

### Use the jobcoin mixer
If I'm a malicious user creates many fake transactions TODO

## Implementation
Much of the logic is between the MixingActor and TransactionActor.

Besides that there is JobcoinMixer and JobcoinWebService.

JobcoinMixer initializes the MixingActor and acts on the console.

JobcoinWebService provides a service to interact with the Jobcoin API.

TransactionActors just move a request to tumble funds through it's stages and 
is managed by the single MixingActor.

The MixingActor manages each Transaction and attempts some level of obscurity.

### TransactionActor
TODO
### MixingActor
TODO

### Tests 

## TODOs

These are things I would want to do if I had more time or might do before submission.

### Persistance

If the jobcoin mixer dies I would want to recover and retry any unfinished transactions.

### Better recovery mechanism when the Jobcoin API is down

Right now I'm simply attempting a constant amount of times and just counting failures

### Handle when a user does not have enough funds in the Tumbler's deposit address

### Dependency injection

### Better tests
Test JobcoinWebService

