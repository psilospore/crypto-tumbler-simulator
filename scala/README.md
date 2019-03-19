# Jobcoin Tumbler

A simple tumbler that runs on Jobcoin.

## Developer Notes

I've attempted to explain any non-common patterns, or usages of libraries outside of the provided libraries.
Such as my comment on extension methods or on Cats.

### Run
`sbt run`


### Test
`sbt test`

## Mixing 

## TODOs

These are things I would want to do if I had more time or might do before submission.

### Persistance

If the jobcoin mixer dies I would want to recover and retry any unfinished transactions.

### Better recovery mechanism when the Jobcoin API is down

Right now I'm simply attempting a constant amount of times and just counting failures

### Handle when a user does not have enough funds in the Tumbler's deposit address

### Dependency injection

### Better tests
