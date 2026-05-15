# Issues found/Bug list

Local-only bug list, not committed. Use this to track issues found or bugs to be fixed.

---

## 1. Strengthen backup and recovery

On building from different branch and testing with existing data, data backup on the cloud is messed up. All the transactions, categories, merchants are loss. Backup and recovery needs strengthening, perhaps have a few backup files, or version backup data so when database changes it won't override or change any existing value. Other suggestions is up for discussion.

## 2. Incorrect and inconsistent push notification

I have set push notification on daily basis, updated 8pm timing to 9pm. On the time of changing, it was 8:35pm, when 9pm arrived there were no push notification. The next day also failed to get any notification. It was on the third day 7:45pm that I got a push notification. Debug and find out why.

## 3. Amount parsing

I got transaction notification from CIMB: `CIMB:MYR 1163.27 was charged on your card num...` and the amount tracked is 116.00, why is this parsing incorrect and how to mitigate it?

## 4. Smarter Improvement

How to improve the app in general to be smarter? I need transaction to auto categorize, auto predict description and merchant note (that is editable) and even ignore non-transaction notification when CAPTURE_ALL is turned on.
