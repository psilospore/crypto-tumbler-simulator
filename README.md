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

Displays a console with more information.

### Test
`sbt test`

### Console
`sbt console`

## Important info on usage

I built this assuming this tumbler would be continuously receiving
requests.
Requests to tumble funds are batched in groups of 5 (see the Mixing Actor documentation for more info).
Your initial request to tumble funds won't be processed until there are 4 more on added on the queue.

Also if there are multiple safe addresses those will be paid out in different batches each.
To make it a bit easier to deal with I've created the script to generate random requests.

## Example


```
tumble silk-1,salk-2,balk-3
//deposit to return address

// The following is to handle batches
generate-random 5 1 //pays out to silk-1 after some time
//wait 10 seconds
generate-random 5 1 //pays out to salk-2 after some time
//wait 10 seconds
generate-random 5 1 //pays out to balk-3 after some time
```

[Screenshots](./pics)

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

You can also examine transactions by time. If my tumbler takes in funds

### Vulnerabilities in jobcoin itself

Seems like there's no security. 

### Use the jobcoin mixer
If I'm a malicious user I could make many fake requests to the jobcoin
mixer, and filter those outs from the house address.
My jobcoin mixer would be vulnerable to this kind of attack.

There's more 

## Implementation
Much of the logic is between the MixingActor and TransactionActor.

Besides that there is JobcoinMixer and JobcoinWebService.

JobcoinMixer initializes the MixingActor and acts on the console.

JobcoinWebService provides a service to interact with the Jobcoin API.

TransactionActors just move a request to tumble funds through it's stages and 
is managed by the single MixingActor.

The MixingActor manages each Transaction and attempts some level of obscurity.

### TransactionActor
Move funds though it's various stages, and interacts with the Jobcoin API.
Waiting for deposit
Transferring to house
Waiting for mixing actor to transfer to each safe address

See transaction actor for more info.

### MixingActor

Manages transactions and has most of the mixing logic.

See mixing actor for more info

### Tests 

